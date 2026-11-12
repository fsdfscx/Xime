package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Intent
import android.graphics.RectF
import android.os.Build
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

    /** 方向键扩选的回执等待代次：新按键或退出选择模式后，旧回执作废。 */
    private var selectionReceiptToken = 0

    /** 连续收不到扩选回执的次数（按包名），达到阈值后该 App 直接走降级路径。 */
    private val dpadMissStreak = mutableMapOf<String, Int>()

    /** 已确认不支持 Shift+方向键扩选的包名。 */
    private val dpadUnsupportedPackages = mutableSetOf<String>()

    /** 已确认不支持 requestTextBoundsInfo 的包名。 */
    private val textBoundsUnsupportedPackages = mutableSetOf<String>()

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
                ensureSelectionState(snapshot)
                moveCaretTo(ic, lineStartAt(snapshot.text, currentCaret(snapshot)))
            }
            "end" -> {
                val snapshot = snapshotSelection(ic)
                ensureSelectionState(snapshot)
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
                selectionReceiptToken++
                service.setSelectionCursorMonitoring(true)
            }
            "select_end" -> {
                selectionReceiptToken++
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
     * 上下扩展选区交给宿主按视觉行处理：发送完整的 Shift 按下 + 方向键 + Shift 抬起序列。
     *
     * 只给方向键带 META_SHIFT_ON 时，部分编辑器（MT 管理器、便签等）会忽略 Shift 并折叠选区；
     * 因此显式发送 SHIFT_LEFT 按下/抬起，由编辑器按自己的 Layout 决定视觉行。
     */
    private fun extendSelectionByArrowKey(ic: InputConnection, keyCode: Int) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_SHIFT_ON))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    /**
     * 上下扩展选区：优先委托宿主（Shift + 方向键），收不到回执时按包名降级。
     *
     * 单次无变化不算失败：光标已在文首/文末、或宿主回传有延迟都会出现。
     * 连续 [DPAD_MISS_LIMIT] 次无变化才认定该 App 不认扩选，避免误判一次就全局切到
     * 几何路径——那会让后续按键跳过视觉行甚至整段选中。
     */
    private fun extendSelectionToAdjacentLine(ic: InputConnection, direction: Int) {
        val snapshot = snapshotSelection(ic)
        ensureSelectionState(snapshot)
        if (editSelAnchor < 0 || editSelExtent < 0) return
        val total = snapshot.totalLength
        // 快照不完整（宿主只返回局部文本）时绝对坐标不可信，不移动
        if (editSelAnchor > total || editSelExtent > total) return

        val packageName = service.currentInputEditorInfo?.packageName ?: ""
        if (packageName in dpadUnsupportedPackages) {
            fallbackSelectionMove(ic, direction, packageName)
            return
        }

        val baseStart = snapshot.beforeLength
        val baseEnd = snapshot.maxPosition
        val pressAt = SystemClock.uptimeMillis()
        val token = ++selectionReceiptToken
        extendSelectionByArrowKey(
            ic,
            if (direction < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
        )
        service.mainHandler.postDelayed({
            if (token != selectionReceiptToken) return@postDelayed
            if (selectionMoved(ic, baseStart, baseEnd, pressAt)) {
                dpadMissStreak[packageName] = 0
                return@postDelayed
            }
            val streak = (dpadMissStreak[packageName] ?: 0) + 1
            dpadMissStreak[packageName] = streak
            if (streak >= DPAD_MISS_LIMIT && packageName.isNotEmpty()) {
                dpadUnsupportedPackages.add(packageName)
                FileLogger.w(
                    XimeInputMethodService.TAG,
                    "Shift+方向键扩选无回执，后续改用几何/逻辑行: $packageName",
                )
            }
            // 回执可能晚到：降级前再读一次真实选区，避免重复移动
            val confirmed = snapshotSelection(ic)
            if (confirmed.beforeLength != baseStart || confirmed.maxPosition != baseEnd) {
                ensureSelectionState(confirmed)
                return@postDelayed
            }
            ensureSelectionState(confirmed)
            fallbackSelectionMove(ic, direction, packageName)
        }, DPAD_RECEIPT_DELAY_MS)
    }

    /** 方向键扩选是否生效：优先用宿主 onUpdateSelection 回执，缺失时再读一次快照。 */
    private fun selectionMoved(
        ic: InputConnection,
        baseStart: Int,
        baseEnd: Int,
        pressAt: Long,
    ): Boolean {
        val (start, end, at) = service.reportedSelection()
        if (at >= pressAt && start >= 0 && end >= 0) {
            return start != baseStart || end != baseEnd
        }
        val now = snapshotSelection(ic)
        return now.beforeLength != baseStart || now.maxPosition != baseEnd
    }

    /** 降级：优先精确几何（Android 14+），不可用再用逻辑行。 */
    private fun fallbackSelectionMove(ic: InputConnection, direction: Int, packageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            packageName !in textBoundsUnsupportedPackages
        ) {
            val anchorInfo = service.latestCursorAnchorInfo()
            if (requestGeometricMove(ic, direction, packageName, anchorInfo)) return
        }
        heuristicSelectionMove(ic, direction)
    }

    /** 一次几何移动请求所需的上下文。 */
    private class GeometryMove(
        val ic: InputConnection,
        val caretX: Float,
        val targetY: Float,
        val extent: Int,
        val direction: Int,
        val packageName: String,
        val token: Int,
        val total: Int,
    )

    /**
     * 路径 B：用 CursorAnchorInfo 定位光标行的几何，再请求附近逐字符矩形，
     * 由编辑器给出相邻视觉行同列位置。返回 true 表示请求已发出（结果异步）。
     */
    private fun requestGeometricMove(
        ic: InputConnection,
        direction: Int,
        packageName: String,
        anchorInfo: CursorAnchorInfo?,
    ): Boolean {
        if (editSelAnchor < 0 || editSelExtent < 0 || anchorInfo == null) return false
        val selStart = anchorInfo.selectionStart
        val selEnd = anchorInfo.selectionEnd
        if (selStart >= 0 && selEnd >= 0) {
            val known = setOf(editSelAnchor, editSelExtent)
            if (selStart !in known || selEnd !in known) return false
        }
        val extent = editSelExtent
        val caretBox = anchorInfo.getCharacterBounds(extent)
        val prevBox = if (extent > 0) anchorInfo.getCharacterBounds(extent - 1) else null
        val markerTop = anchorInfo.insertionMarkerTop
        val markerBottom = anchorInfo.insertionMarkerBottom
        val lineTop = if (!markerTop.isNaN()) markerTop else (caretBox ?: prevBox)?.top ?: return false
        val lineBottom = if (!markerBottom.isNaN()) markerBottom else (caretBox ?: prevBox)?.bottom ?: return false
        if (lineBottom <= lineTop) return false
        val markerX = anchorInfo.insertionMarkerHorizontal
        val caretX = when {
            !markerX.isNaN() -> markerX
            caretBox != null -> caretBox.left
            prevBox != null -> prevBox.right
            else -> return false
        }
        val lineHeight = lineBottom - lineTop
        val targetY = if (direction < 0) lineTop - lineHeight / 2f else lineBottom + lineHeight / 2f
        val bounds = RectF(
            -VISUAL_QUERY_HORIZONTAL,
            lineTop - VISUAL_QUERY_LINES * lineHeight,
            VISUAL_QUERY_HORIZONTAL,
            lineBottom + VISUAL_QUERY_LINES * lineHeight,
        )
        // getTextAfterCursor 以选区右端为基准，总长要按较大的那个端点累加
        val total = maxOf(editSelAnchor, editSelExtent) +
            (ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0)?.length ?: 0)
        sendGeometryRequest(
            GeometryMove(ic, caretX, targetY, extent, direction, packageName, selectionReceiptToken, total),
            bounds,
            retried = false,
        )
        return true
    }

    private fun sendGeometryRequest(move: GeometryMove, bounds: RectF, retried: Boolean) {
        var settled = false
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            onGeometryUnavailable(move, retried)
        }
        service.mainHandler.postDelayed(timeout, TEXT_BOUNDS_TIMEOUT_MS)
        val sent = service.requestTextBoundsInfo(bounds) { info ->
            if (settled) return@requestTextBoundsInfo
            settled = true
            service.mainHandler.removeCallbacks(timeout)
            if (move.token != selectionReceiptToken) return@requestTextBoundsInfo
            if (info == null || info.startIndex > move.extent || info.endIndex < move.extent) {
                onGeometryUnavailable(move, retried)
                return@requestTextBoundsInfo
            }
            val target = runCatching { info.getOffsetForPosition(move.caretX, move.targetY) }
                .getOrDefault(-1)
            if (target < 0 || target == move.extent ||
                target > move.total ||
                kotlin.math.abs(target - move.extent) > MAX_FALLBACK_JUMP
            ) {
                onGeometryUnavailable(move, retried)
            } else {
                moveSelectionExtent(move.ic, target)
            }
        }
        if (!sent) {
            service.mainHandler.removeCallbacks(timeout)
            if (!settled) {
                settled = true
                onGeometryUnavailable(move, retried)
            }
        }
    }

    /** 几何请求失败：先用全文本矩形重试一次，仍失败则拉黑该包名并退回逻辑行。 */
    private fun onGeometryUnavailable(move: GeometryMove, retried: Boolean) {
        if (move.token != selectionReceiptToken) return
        if (!retried) {
            sendGeometryRequest(
                move,
                RectF(
                    -VISUAL_QUERY_HORIZONTAL,
                    -VISUAL_QUERY_HORIZONTAL,
                    VISUAL_QUERY_HORIZONTAL,
                    VISUAL_QUERY_HORIZONTAL,
                ),
                retried = true,
            )
            return
        }
        if (move.packageName.isNotEmpty()) {
            textBoundsUnsupportedPackages.add(move.packageName)
        }
        FileLogger.w(
            XimeInputMethodService.TAG,
            "requestTextBoundsInfo 不可用，改用逻辑行: ${move.packageName}",
        )
        heuristicSelectionMove(move.ic, move.direction)
    }

    /** 路径 C：没有任何几何信息时按 \n 逻辑行移动，宁可粒度粗也不猜视觉行。 */
    private fun heuristicSelectionMove(ic: InputConnection, direction: Int) {
        val snapshot = snapshotSelection(ic)
        ensureSelectionState(snapshot)
        if (editSelAnchor < 0 || editSelExtent < 0) return
        val pos = editSelExtent.coerceIn(0, snapshot.text.length)
        val target = heuristicVerticalTarget(snapshot.text, pos, direction) ?: return
        if (target != editSelExtent) moveSelectionExtent(ic, target)
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

/** 方向键扩选的等待回执时间：短了会误判，长了首次按键延迟明显。 */
private const val DPAD_RECEIPT_DELAY_MS = 180L

/** 连续多少次收不到回执才认定该 App 不支持 Shift+方向键扩选。 */
private const val DPAD_MISS_LIMIT = 3

private const val TEXT_BOUNDS_TIMEOUT_MS = 150L

/** 几何移动的目标不允许离光标太远，防止宿主返回错误矩形时整段跳转。 */
private const val MAX_FALLBACK_JUMP = 512

private const val VISUAL_QUERY_LINES = 40f
private const val VISUAL_QUERY_HORIZONTAL = 100000f

/**
 * 路径 C：没有几何信息时按 \n 逻辑行移动；行短时钳到行尾。
 *
 * 不按字符数估算软换行——中文没有词间空格，估算会稳定偏一两个字，
 * 这种"偶尔差一点"比粒度粗更让人难以接受。
 */
internal fun heuristicVerticalTarget(text: String, pos: Int, direction: Int): Int? {
    if (text.isEmpty()) return null
    val clamped = pos.coerceIn(0, text.length)
    val lineStart = lineStartAt(text, clamped)
    val column = clamped - lineStart
    if (direction < 0) {
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
