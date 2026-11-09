package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轮换目标：已启用方案列表中当前方案的下一个（末尾回到首个）。
 *
 * 当前方案不在列表内（列表过期/刚部署完）时取首个；列表为空返回 null。
 */
internal fun nextSchemaId(schemaIds: List<String>, currentSchemaId: String): String? {
    if (schemaIds.isEmpty()) return null
    val index = schemaIds.indexOf(currentSchemaId)
    return schemaIds[(index + 1) % schemaIds.size]
}

/**
 * 方案管理与输入模式切换。
 *
 * 承载方案切换（switchSchema/applyPageSizeSetting）、部署（reloadConfig）、
 * 工具栏编辑动作、键盘高度与浮动模式调整。
 * 中英切换已收敛至 [AsciiModeController]。
 * 共享状态通过 service 引用访问。
 */
internal class ImeSchemaController(private val service: XimeInputMethodService) {
    internal fun reloadConfig() {

        service.mainHandler.post {
            service.requestHideSelf(0)
            android.widget.Toast.makeText(service, tr(R.string.s_fanganbushuzhong), android.widget.Toast.LENGTH_SHORT).show()
        }

        // 部署投递到 key-processing 队列：与按键/切换同队列串行执行，
        // 部署期间输入/切换操作排队等待，完成后自动恢复，
        // 不再因 rimeLock 被部署占用而失败或静默丢弃。
        service.keyRouter.postRimeJob {
            try {
                KeysConfigHelper.loadConfig(service)
                // 重新加载配色方案（用户可能在 xime.custom.yaml 中修改了 color_schemes）
                KeyboardThemes.reload(service)

                // 统一部署入口（设置页/键盘菜单/安装替换同一条路径）：
                // 内部会镜像 schema_list、补个人词库、重启维护并在维护前后重建会话。
                val result = SchemeDeployer.deploy(service)
                val deployed = result.ok
                val missing = RimeConfigHelper.missingCompiledSchemaNames(service)

                // 部署完成后重新加载配置（Rime 可能在部署过程中改写文件）
                KeysConfigHelper.loadConfig(service)
                KeyboardThemes.reload(service)

                val availableSchemas = service.rimeEngine.getAvailableSchemas()

                val savedSchema = SettingsPreferences.getCurrentSchema(service)
                // 部署刚结束，get_schema_list 就是本次部署的结果，直接按它判定。
                if (savedSchema in availableSchemas) {
                    applyPageSizeSetting(savedSchema)
                    service.rimeEngine.switchSchema(savedSchema)
                    // 部署会重建会话；当前若是 english 方案，保持其 ascii 默认值。
                    service.asciiModeController.applySchemaDefault(savedSchema)
                } else {
                    FileLogger.w(
                        XimeInputMethodService.TAG,
                        "Schema $savedSchema not in deployed schema_list, " +
                            "available=${availableSchemas.joinToString()}"
                    )
                }

                // 直接在 key-processing 线程同步读取 name，避免嵌套协程的时序问题
                val currentSchemaId = service.rimeEngine.getCurrentSchema()
                val schemaName = SchemaManager.getSchemaDisplayName(
                    service, currentSchemaId
                ) ?: currentSchemaId

                // 部署会重建 build/，方案列表（含「已编译」过滤）必须重新查询，
                // 否则菜单仍显示部署前的旧快照。
                service.sessionController.updateSchemaName()

                withContext(Dispatchers.Main) {
                    service.uiState.value = service.uiState.value.copy(
                        schemaName = schemaName,
                        currentSchemaId = currentSchemaId,
                    )
                    service.updateUI()
                    val message = when {
                        !deployed -> tr(R.string.s_bushushibai)
                        missing.isNotEmpty() ->
                            tr(R.string.s_bushushibai_fanganweibianyi, missing.joinToString("、"))
                        else -> tr(R.string.s_fanganbushuwancheng)
                    }
                    android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to reload config", e)
            }
        }
    }

    internal fun openSettings() {
        try {
            val intent = Intent(service, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to open settings", e)
        }
    }
    
    private var editSelAnchor = -1
    private var editSelExtent = -1

    /**
     * 选区快照：选区前文 + 选中文本 + 选区后文拼回完整文本。
     *
     * getTextBeforeCursor 以选区左端为基准、getTextAfterCursor 以选区右端为基准，
     * 直接相加会跳过选区本身。
     */
    private class SelectionSnapshot(
        private val before: CharSequence,
        private val selected: CharSequence?,
        private val after: CharSequence,
    ) {
        val beforeLength: Int get() = before.length
        val selectedLength: Int? get() = selected?.length
        val afterLength: Int get() = after.length
        val maxPosition: Int get() = beforeLength + (selectedLength ?: 0)
        val totalLength: Int get() = beforeLength + (selectedLength ?: 0) + afterLength

        // 左右扩展只需长度；行移动才真正拼接全文，避免每次按键复制整段文本
        val text: String by lazy { before.toString() + (selected?.toString() ?: "") + after.toString() }
    }

    private fun snapshotSelection(ic: InputConnection): SelectionSnapshot {
        val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        return SelectionSnapshot(
            before = before,
            selected = ic.getSelectedText(0),
            after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: "",
        )
    }

    internal fun handleToolbarEditingAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "home" -> {
                val snapshot = snapshotSelection(ic)
                moveCaretTo(ic, lineStartAt(snapshot.text, currentCaret(snapshot)))
            }
            "end" -> {
                val snapshot = snapshotSelection(ic)
                moveCaretTo(ic, lineEndAt(snapshot.text, currentCaret(snapshot)))
            }
            "arrow_up" -> sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_UP)
            "arrow_down" -> sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "arrow_left" -> sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "arrow_right" -> sendArrowKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT)

            "select_begin" -> {
                val snapshot = snapshotSelection(ic)
                editSelAnchor = snapshot.beforeLength
                editSelExtent = snapshot.maxPosition
                service.setSelectionCursorMonitoring(true)
            }
            "select_end" -> {
                editSelAnchor = -1
                editSelExtent = -1
                service.setSelectionCursorMonitoring(false)
            }
            "select_arrow_left" -> extendSelection(ic, -1)
            "select_arrow_right" -> extendSelection(ic, 1)
            "select_arrow_up" -> extendSelectionToAdjacentLine(ic, -1)
            "select_arrow_down" -> extendSelectionToAdjacentLine(ic, 1)
        }
    }

    /**
     * 发送方向键；光标已在文档边界时直接吞掉。
     *
     * 宿主未消费的方向键会变成焦点导航，导致输入框失焦、输入法键盘被收起。
     */
    private fun sendArrowKey(ic: InputConnection, keyCode: Int) {
        if (ic.getSelectedText(0) == null) {
            val before = ic.getTextBeforeCursor(1, 0)
            val after = ic.getTextAfterCursor(1, 0)
            val atStart = before != null && before.isEmpty()
            val atEnd = after != null && after.isEmpty()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> if (atStart) return
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> if (atEnd) return
            }
        }
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun extendSelection(ic: InputConnection, direction: Int) {
        val snapshot = snapshotSelection(ic)
        ensureSelectionState(snapshot)
        val width = kotlin.math.abs(editSelExtent - editSelAnchor)
        // 宿主不返回选中文本时，用自维护的选区宽度补齐被 before/after 跳过的那段
        val total = if (snapshot.selectedLength != null) {
            snapshot.totalLength
        } else {
            snapshot.beforeLength + width + snapshot.afterLength
        }
        val extent = editSelExtent.coerceIn(0, total)
        val next = (extent + direction).coerceIn(0, total)
        if (next == extent) return
        moveSelectionExtent(ic, next)
    }

    /** 状态缺失、或宿主自行改过选区时，用当前选区同步活动端（锚点保持为选区起点）。 */
    private fun ensureSelectionState(snapshot: SelectionSnapshot) {
        if (editSelAnchor < 0 || editSelExtent < 0) {
            editSelAnchor = snapshot.beforeLength
            editSelExtent = snapshot.maxPosition
            return
        }
        val selectedLength = snapshot.selectedLength
        if (selectedLength == null) {
            // 无选区（或宿主不返回选中文本）：光标即活动端
            editSelAnchor = snapshot.beforeLength
            editSelExtent = snapshot.beforeLength
            return
        }
        val min = snapshot.beforeLength
        val max = min + selectedLength
        editSelExtent = when (editSelAnchor) {
            min -> max
            max -> min
            else -> {
                editSelAnchor = min
                max
            }
        }
    }

    private fun moveSelectionExtent(ic: InputConnection, target: Int) {
        editSelExtent = target
        ic.beginBatchEdit()
        ic.setSelection(editSelAnchor, target)
        ic.endBatchEdit()
    }

    /**
     * 上下移动选择活动端：优先用 CursorAnchorInfo 按视觉行移动，信息不足时回退逻辑行。
     *
     * 自动折行的长段落需要视觉行；纯逻辑行（\n）会一次跨越多行。
     */
    private fun extendSelectionToAdjacentLine(ic: InputConnection, direction: Int) {
        val snapshot = snapshotSelection(ic)
        if (editSelAnchor < 0 || editSelExtent < 0) ensureSelectionState(snapshot)
        if (editSelAnchor < 0 || editSelExtent < 0) return
        val total = snapshot.totalLength
        // 状态位置超出快照范围：宿主只返回了局部文本，绝对坐标不可信，不能移动
        if (editSelAnchor > total || editSelExtent > total) return
        val extent = editSelExtent
        val fallback: () -> Unit = {
            verticalTarget(snapshot.text, extent, direction)?.let { moveSelectionExtent(ic, it) }
        }
        // 选择模式持续监听时，缓存信息通常就是当前选区，直接使用避免一帧延迟
        val cached = service.latestCursorAnchorInfo()
        if (cached != null) {
            val target = visualLineTarget(cached, extent, direction)
            if (target != null && target in 0..total) {
                if (target != editSelExtent) moveSelectionExtent(ic, target)
                return
            }
        }
        service.requestCursorAnchorInfo(
            onInfo = { info ->
                val target = visualLineTarget(info, extent, direction)
                if (target != null && target in 0..total) {
                    if (target != editSelExtent) moveSelectionExtent(ic, target)
                } else {
                    fallback()
                }
            },
            onUnavailable = fallback,
        )
    }

    /**
     * 从 CursorAnchorInfo 的字符矩形找相邻视觉行同水平位置的文本下标。
     * 返回 null 表示信息不足或已到首/末视觉行。
     */
    private fun visualLineTarget(info: CursorAnchorInfo, caretHint: Int, direction: Int): Int? {
        val caret = caretHint.coerceAtLeast(0)
        // 宿主回报的选区端点必须与自维护的选区一致，否则坐标/索引体系不同，不能采信
        val hostStart = info.selectionStart
        val hostEnd = info.selectionEnd
        if (hostStart >= 0 && hostEnd >= 0) {
            val known = setOf(editSelAnchor, editSelExtent)
            if (hostStart !in known || hostEnd !in known) return null
        }
        val before = if (caret > 0) info.getCharacterBounds(caret - 1) else null
        val after = info.getCharacterBounds(caret)
        // 光标两侧都拿不到字符矩形：宿主没有可用的布局信息
        if (before == null && after == null) return null
        val fallbackBounds = before ?: after ?: return null
        val markerTop = info.insertionMarkerTop
        val markerBottom = info.insertionMarkerBottom
        val markerX = info.insertionMarkerHorizontal
        val hasMarker = !markerTop.isNaN() && !markerBottom.isNaN()
        val caretTop = if (hasMarker) markerTop else fallbackBounds.top
        val caretBottom = if (hasMarker) markerBottom else fallbackBounds.bottom
        if (caretBottom <= caretTop) return null
        val onCaretLine = { rect: RectF ->
            rect.bottom > caretTop - 1f && rect.top < caretBottom + 1f
        }
        val anchorBox = after?.takeIf(onCaretLine) ?: before?.takeIf(onCaretLine)
        // 光标位置与字符矩形不在同一行：索引/坐标系不可信，回退逻辑行
        if (hasMarker && anchorBox == null) return null
        val caretX = when {
            !markerX.isNaN() -> markerX
            direction < 0 -> anchorBox?.right ?: return null
            else -> anchorBox?.left ?: return null
        }
        val boxes = ArrayList<CharBox>()
        var misses = 0
        if (direction < 0) {
            var index = caret - 1
            while (index >= 0 && index >= caret - MAX_VISUAL_SCAN && misses < MAX_BOUNDS_MISSES) {
                val box = info.getCharacterBounds(index)?.toCharBox(index, info.getCharacterBoundsFlags(index))
                if (box == null) {
                    misses++
                } else {
                    misses = 0
                    boxes.add(box)
                }
                index--
            }
        } else {
            var index = caret
            while (index <= caret + MAX_VISUAL_SCAN && misses < MAX_BOUNDS_MISSES) {
                val box = info.getCharacterBounds(index)?.toCharBox(index, info.getCharacterBoundsFlags(index))
                if (box == null) {
                    misses++
                } else {
                    misses = 0
                    boxes.add(box)
                }
                index++
            }
        }
        return findVisualLineTarget(boxes, caret, caretX, caretTop, caretBottom, direction)
    }

    private fun currentCaret(snapshot: SelectionSnapshot): Int =
        (if (editSelExtent >= 0) editSelExtent else snapshot.beforeLength)
            .coerceIn(0, snapshot.text.length)

    /** Home/End：折叠选区到目标行边界；选择模式下同步活动端。 */
    private fun moveCaretTo(ic: InputConnection, target: Int) {
        if (editSelAnchor >= 0) {
            editSelAnchor = target
            editSelExtent = target
        }
        ic.beginBatchEdit()
        ic.setSelection(target, target)
        ic.endBatchEdit()
    }

    internal fun applyPageSizeSetting(schemaId: String) {
        val userPageSize = SettingsPreferences.getPageSize(service)
        if (userPageSize > 0) {
            service.rimeEngine.setPageSize(schemaId, userPageSize)
        }
    }

    internal fun switchSchema(schemaId: String) {
        if (isHandwritingSchema(schemaId)) {
            // 检查手写模型文件是否已下载
            if (!com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service)) {
                FileLogger.w(XimeInputMethodService.TAG, "Handwriting model not found, redirecting to download")
                android.widget.Toast.makeText(
                    service, tr(R.string.s_qingxianxiazaishouxiemoxing), android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(
                    service, com.kingzcheung.xime.MainActivity::class.java
                ).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("open_fragment", "model_management")
                }
                service.startActivity(intent)
                return
            }
            service.previousSchemaId = service.rimeEngine.getCurrentSchema()
            SchemeStore.setCurrent(service, schemaId)
            service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
            // 手写模型按"用键盘时加载"管理：切到手写方案即展示手写键盘，
            // 由 HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载，
            // 此处不重复加载（避免与布局初始化并发双 bind/load）
            service.sessionController.updateSchemaName()
            return
        }
        // 切离手写方案时释放手写引擎
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
        try {
            SchemeStore.setCurrent(service, schemaId)
            // 用户自定义候选词数：先写 custom.yaml 再切方案，Rime 会自动加载
            applyPageSizeSetting(schemaId)
            // 部署/编译进行中 switchSchema 返回 false（不阻塞等待），
            // 此时不应继续触发其他 native 调用进入编译中的引擎
            if (!service.rimeEngine.switchSchema(schemaId)) {
                if (service.rimeEngine.isMaintaining()) {
                    FileLogger.w(XimeInputMethodService.TAG, "switchSchema skipped: deployment in progress")
                    Toast.makeText(service, tr(R.string.s_cikubushuzhong_qingshaohouzaiq_7d5ce), Toast.LENGTH_SHORT).show()
                    return
                }
                // 引擎切换失败（常见：方案未部署/不在 schema_list，老版本升级残留）。
                // 必须回滚偏好到引擎实际方案：偏好留着目标值会让 UI 状态与引擎
                // 永久脱节（键盘已显示九键但数字键无人处理，候选栏始终 IDLE）。
                // getCurrentSchema 无会话时返回空串——此时不动偏好，保留原值由
                // IME 重启的恢复逻辑兜底，避免把空串写进偏好与 user.yaml
                val actual = service.rimeEngine.getCurrentSchema()
                if (actual.isNotEmpty()) {
                    SchemeStore.setCurrent(service, actual)
                }
                FileLogger.w(XimeInputMethodService.TAG, "switchSchema failed: target=$schemaId actual=$actual")
                Toast.makeText(service, tr(R.string.s_fanganweibushu_qingzaifangangu_63cf5), Toast.LENGTH_SHORT).show()
                return
            }
            val ascii = service.asciiModeController.applySchemaDefault(schemaId)
            // 标点默认值也由方案声明：ascii_punct reset:1 时不覆盖；
            // 未声明或 reset:0 的普通中文方案保持原有「非 ascii 时用中文标点」。
            if (!ascii && SchemaManager.schemaSwitchReset(service, schemaId, "ascii_punct") != 1) {
                service.rimeEngine.setOption("ascii_punct", false)
            }
            service.uiState.value = service.uiState.value.copy(isAsciiMode = ascii)
            service.sessionController.updateSchemaName()
            service.updateUI()
            // 确保键盘布局与方案匹配（如 T9 九键不应被 switchMain 重置为全键盘）
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(
                    ascii, schemaId
                )
            )
            // 切换成功不弹 Toast：键盘/候选栏已经能看出当前方案，避免打断输入
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to switch schema", e)
        }
    }
    
    /**
     * 轮换切换到下一个已启用方案（菜单栏方案列表顺序）。
     *
     * 复用 [switchSchema]：中英模式、键盘类型、手写模型校验等行为与在菜单里选方案一致。
     * 只有一个方案（或列表尚未就绪）时不做任何事。
     */
    internal fun cycleSchema() {
        val state = service.uiState.value
        val target = nextSchemaId(state.schemas.map { it.schemaId }, state.currentSchemaId)
        if (target == null || target == state.currentSchemaId) {
            FileLogger.i(
                XimeInputMethodService.TAG,
                "cycleSchema skipped: current=${state.currentSchemaId}, " +
                    "enabled=${state.schemas.joinToString { it.schemaId }}"
            )
            return
        }
        switchSchema(target)
    }

    private fun downloadSchema(schemaId: String) {
        service.serviceScope.launch(Dispatchers.IO) {
            service.notifyDeploymentStatus(true, tr(R.string.s_zhengzaixiazai, schemaId))
            
            val success = SchemaConfigHelper.downloadSchema(service, schemaId)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(service, tr(R.string.s_xiazaichenggong_qingdianjibushu, schemaId), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(service, tr(R.string.s_xiazaishibai_2, schemaId), Toast.LENGTH_SHORT).show()
                }
                service.notifyDeploymentStatus(false, "")
            }
        }
    }
    
    internal fun setKeyboardHeight(heightDp: Int) {
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setKeyboardHeightDp(service, heightDp, isLandscape)
        service.uiState.value = service.uiState.value.copy(keyboardHeightDp = heightDp)
        // 不弹 Toast：调节界面本身就是实时预览，确定时键盘已经变成目标高度，
        // 再提示"键盘高度已调整"是多余噪音
    }

    internal fun toggleFloatingMode(enabled: Boolean, floatingMinY: Int = 0) {
        if (enabled && service.isHostConstrained()) {
            // 独立 IME 面板：承载区比键盘还小，浮动卡片没有可拖动/可停靠的空间
            android.widget.Toast.makeText(
                service,
                tr(R.string.s_dangqianxianshiquyubuzhichifud_d72e1),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 任何路径切悬浮都清掉拖动提示态（正常由拖动松手消费，这里兜底防光效残留）
        service.floatingExitHintState.value = false
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setFloatingMode(service, enabled, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(service, isLandscape)
        val screenW = service.resources.configuration.screenWidthDp
        val screenH = service.resources.configuration.screenHeightDp
        val portraitWidth = minOf(screenW, screenH)
        val halfMargin = FloatingCardGeometry.halfMarginDp(screenW, portraitWidth)
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        service.uiState.value = service.uiState.value.copy(
            isFloatingMode = enabled,
            floatingOffsetX = clampedX,
            // 开启悬浮归位到"拖动可达的最低点"：拖动钳制下界是 floatingMinY（导航栏高），
            // 归位到 0 会停在拖动到不了的位置（表现为比可达范围偏下一截）
            floatingOffsetY = if (enabled) floatingMinY else 0,
        )
        if (enabled) {
            service.closeToolPanel()
            // 清掉上一轮的实测矩形：新卡片矩形未建立前，触摸区/钳制走 FloatingCardGeometry 兜底
            service.floatingCardBounds = null
            service.currentFloatingCardHeightDp = 0
        }
        service.applyWindowBackground()
    }

}

/** 光标所在逻辑行（以 \n 分行）的行首下标。 */
internal fun lineStartAt(text: String, pos: Int): Int {
    if (text.isEmpty()) return 0
    val clamped = pos.coerceIn(0, text.length)
    val newline = text.lastIndexOf('\n', clamped - 1)
    return if (newline >= 0) newline + 1 else 0
}

/** 光标所在逻辑行的行尾下标（不含换行符）。 */
internal fun lineEndAt(text: String, pos: Int): Int {
    val clamped = pos.coerceIn(0, text.length)
    val newline = text.indexOf('\n', clamped)
    return if (newline >= 0) newline else text.length
}

/**
 * 上下移动时相邻逻辑行的同列目标位置。
 *
 * 行短时钳到行尾；已在首行/末行（含单行文本）返回 null，表示原地不动。
 */
internal fun verticalTarget(text: String, pos: Int, direction: Int): Int? {
    if (text.isEmpty()) return null
    val clamped = pos.coerceIn(0, text.length)
    val column = clamped - lineStartAt(text, clamped)
    if (direction < 0) {
        val lineStart = lineStartAt(text, clamped)
        if (lineStart == 0) return null
        val prevLineEnd = lineStart - 1
        val prevLineStart = lineStartAt(text, prevLineEnd)
        return prevLineStart + minOf(column, prevLineEnd - prevLineStart)
    }
    val lineEnd = lineEndAt(text, clamped)
    if (lineEnd >= text.length) return null
    val nextLineStart = lineEnd + 1
    val nextLineEnd = lineEndAt(text, nextLineStart)
    return nextLineStart + minOf(column, nextLineEnd - nextLineStart)
}

/** 视觉行移动时宿主提供的字符矩形。 */
internal data class CharBox(
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isRtl: Boolean = false,
)

private const val MAX_VISUAL_SCAN = 2048
private const val MAX_BOUNDS_MISSES = 4

/** 视觉行移动的目标不允许离光标太远，防止宿主提供错误矩形时整段跳转。 */
private const val MAX_VISUAL_JUMP = 512

private fun RectF.toCharBox(index: Int, flags: Int): CharBox? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    // 宿主未实现字符矩形时可能返回全 0 的空矩形，必须丢弃
    if (right - left <= 0.5f || bottom - top <= 0.5f) return null
    return CharBox(
        index = index,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        isRtl = (flags and CursorAnchorInfo.FLAG_IS_RTL) != 0,
    )
}

/**
 * 在相邻视觉行中找与 caretX 最接近的插入点。
 *
 * boxes 只需包含光标附近字符；返回 null 表示没有相邻行或已到首/末行。
 */
internal fun findVisualLineTarget(
    boxes: List<CharBox>,
    caret: Int,
    caretX: Float,
    caretTop: Float,
    caretBottom: Float,
    direction: Int,
): Int? {
    val validBoxes = boxes.filter {
        it.right - it.left > 0.5f && it.bottom - it.top > 0.5f &&
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
    }
    if (validBoxes.isEmpty()) return null
    val candidates = if (direction < 0) {
        validBoxes.filter { it.bottom <= caretTop + 0.5f }
    } else {
        validBoxes.filter { it.top >= caretBottom - 0.5f }
    }
    if (candidates.isEmpty()) return null
    val targetTop = if (direction < 0) candidates.maxOf { it.top } else candidates.minOf { it.top }
    // 目标必须是真正相邻的另一行，同行的矩形不能当作上一行/下一行
    if (direction < 0 && targetTop >= caretTop - 0.5f) return null
    if (direction > 0 && targetTop <= caretTop + 0.5f) return null
    val lineBoxes = candidates.filter { kotlin.math.abs(it.top - targetTop) < 1f }
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (box in lineBoxes) {
        val startX = if (box.isRtl) box.right else box.left
        val endX = if (box.isRtl) box.left else box.right
        val startDistance = kotlin.math.abs(startX - caretX)
        if (startDistance < bestDistance) {
            bestDistance = startDistance
            bestIndex = box.index
        }
        val endDistance = kotlin.math.abs(endX - caretX)
        if (endDistance < bestDistance) {
            bestDistance = endDistance
            bestIndex = box.index + 1
        }
    }
    return bestIndex
        ?.takeIf { it != caret && kotlin.math.abs(it - caret) <= MAX_VISUAL_JUMP }
}
