package com.ryu.sudoku

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryu.sudoku.ui.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

// ============================================================================
// Engine — pure Kotlin Sudoku generator / solver (faithful to source HTML)
// ============================================================================

object SudokuEngine {

    val DIFF = mapOf("easy" to 40, "medium" to 50, "hard" to 56)
    const val STAGE_MAX = 20

    // Deterministic mulberry32-style PRNG (mirrors HTML rnd()).
    class Rng(seed: Int) {
        private var state: Int = seed
        fun next(): Double {
            state = state + 0x6D2B79F5
            var t = state
            t = (t xor (t ushr 15)) * (1 or t)
            t = t + ((t xor (t ushr 7)) * (61 or t)) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
        fun <T> shuffle(a: MutableList<T>): MutableList<T> {
            for (i in a.size - 1 downTo 1) {
                val j = floor(next() * (i + 1)).toInt()
                val tmp = a[i]; a[i] = a[j]; a[j] = tmp
            }
            return a
        }
    }

    fun idx(r: Int, c: Int) = r * 9 + c

    fun hashStr(s: String): Int {
        var h = 2166136261.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 16777619
        }
        return h
    }

    fun todayStr(): String {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun yesterdayStr(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun ok(g: IntArray, p: Int, v: Int): Boolean {
        val r = p / 9; val c = p % 9
        for (i in 0 until 9) {
            if (g[r * 9 + i] == v || g[i * 9 + c] == v) return false
        }
        val br = (r / 3) * 3; val bc = (c / 3) * 3
        for (y in 0 until 3) for (x in 0 until 3) {
            if (g[(br + y) * 9 + (bc + x)] == v) return false
        }
        return true
    }

    fun genSolved(rng: Rng): IntArray {
        val g = IntArray(81)
        fun fill(p: Int): Boolean {
            if (p >= 81) return true
            if (g[p] != 0) return fill(p + 1)
            val nums = rng.shuffle(mutableListOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
            for (i in 0 until 9) {
                if (ok(g, p, nums[i])) {
                    g[p] = nums[i]
                    if (fill(p + 1)) return true
                    g[p] = 0
                }
            }
            return false
        }
        fill(0)
        return g
    }

    // Solution counter with early-exit at limit + node cap guard.
    fun countSolutions(grid: IntArray, limit: Int): Int {
        val g = grid.copyOf()
        var count = 0
        var nodes = 0
        val cap = 120000
        fun solve(start: Int) {
            if (count >= limit || nodes > cap) return
            var p = start
            while (p < 81 && g[p] != 0) p++
            if (p >= 81) { count++; return }
            for (v in 1..9) {
                if (ok(g, p, v)) {
                    nodes++
                    g[p] = v
                    solve(p + 1)
                    g[p] = 0
                    if (count >= limit || nodes > cap) return
                }
            }
        }
        solve(0)
        return if (nodes > cap) limit else count
    }

    // Stage difficulty curve: stage 1 -> 30 holes, stage 20 -> 58 holes.
    fun stageHoles(n: Int): Int = min(58, 30 + Math.round((n - 1) * 28.0 / 19.0).toInt())

    data class Puzzle(val solution: IntArray, val puzzle: IntArray, val given: BooleanArray)

    fun makePuzzle(gmode: String, diff: String, stage: Int): Puzzle {
        val seed: Int = if (gmode == "daily") {
            hashStr(todayStr() + "_" + diff)
        } else {
            (System.currentTimeMillis().toInt() xor (Random.nextDouble() * 1e9).toInt())
        }
        val rng = Rng(seed)
        val solution = genSolved(rng)
        val puzzle = solution.copyOf()
        val holes = if (gmode == "stage") stageHoles(stage) else (DIFF[diff] ?: 40)
        // 한 칸씩 제거하되, 제거 후에도 '유일해'가 유지될 때만 실제로 비운다.
        // → 난이도(목표 빈칸 수)에 맞춰 빈칸 수가 항상 일정해지고, 유일해도 보장됨.
        //   (기존: 다 비운 뒤 랜덤 복구 → 최종 빈칸 수가 들쭉날쭉해서 쉬움/보통/어려움이 뒤섞이는 버그)
        val cells = rng.shuffle((0 until 81).toMutableList())
        var removed = 0
        var ci = 0
        while (ci < cells.size && removed < holes) {
            val c = cells[ci]; ci++
            if (puzzle[c] == 0) continue
            val backup = puzzle[c]
            puzzle[c] = 0
            if (countSolutions(puzzle, 2) == 1) {
                removed++            // 유일해 유지 → 그대로 비움
            } else {
                puzzle[c] = backup   // 유일해 깨짐 → 되돌림(이 칸은 단서로 남김)
            }
        }
        val given = BooleanArray(81) { puzzle[it] != 0 }
        return Puzzle(solution, puzzle, given)
    }
}

// ============================================================================
// Persistence — SharedPreferences (mirrors localStorage usage in HTML)
// ============================================================================

class SudokuStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sudoku_prefs", Context.MODE_PRIVATE)

    // best: { diff -> seconds }
    fun loadBest(): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        val raw = prefs.getString("sudoku_best_v1", null) ?: return out
        try {
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.getInt(k)
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveBest(best: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in best) o.put(k, v)
        prefs.edit().putString("sudoku_best_v1", o.toString()).apply()
    }

    fun loadStats(): JSONObject {
        val raw = prefs.getString("sudoku_stats_v1", null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    fun saveStats(s: JSONObject) {
        prefs.edit().putString("sudoku_stats_v1", s.toString()).apply()
    }

    fun loadSave(): JSONObject? {
        val raw = prefs.getString("sudoku_save_v1", null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    fun saveGame(json: JSONObject) {
        prefs.edit().putString("sudoku_save_v1", json.toString()).apply()
    }

    fun clearSave() {
        prefs.edit().remove("sudoku_save_v1").apply()
    }

    fun loadHelp(): Boolean = prefs.getString("sudoku_help", "off") == "on"
    fun saveHelp(on: Boolean) {
        prefs.edit().putString("sudoku_help", if (on) "on" else "off").apply()
    }
}

// ============================================================================
// Game state holder (plain mutable class; UI drives via a version counter)
// ============================================================================

class GameState {
    var solution = IntArray(81)
    var puzzle = IntArray(81)
    var given = BooleanArray(81)

    var diff = "easy"
    var gmode = "normal"
    var stage = 1

    var hintsLeft = 3
    var sel = -1
    var elapsed = 0
    var done = false
    var pendingNext = false
}

// ============================================================================
// Activity
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    SudokuApp()
                }
            }
        }
    }
}

// ---- Palette (pastel theme tuned toward the brand accent) ----
private val Bg = Color(0xFFF3F2FB)
private val Accent = Color(0xFF5C6BC0)
private val CardBg = Color(0xFFFFFFFF)
private val TextDark = Color(0xFF3A3550)
private val TextMute = Color(0xFF9B8AAD)
private val GivenColor = Color(0xFF3A3550)
private val EditColor = Color(0xFF5C6BC0)
private val SelBg = Color(0xFFE1E4F5)
private val PeerBg = Color(0xFFF0F0FA)
private val SameBg = Color(0xFFE7E2FF)
private val AltBg = Color(0xFFFAF8FF)
private val BadColor = Color(0xFFFF5F7A)
private val BadBg = Color(0xFFFFE3E8)
private val ThinLine = Color(0x24A082B4)
private val ThickLine = Color(0xFFC9C2E8)
private val NoteColor = Color(0xFFB8A8C8)
private val PulseColor = Color(0x807BE3A8)
private val HintRing = Color(0xFFFFB300)   // 금/앰버 강조 링
private val HintBg = Color(0xFFFFF1C2)     // 힌트 칸 배경 글로우

private fun fmt(s: Int): String {
    val m = s / 60
    val sec = s % 60
    return "$m:" + sec.toString().padStart(2, '0')
}

private fun diffName(d: String): String = when (d) {
    "easy" -> "쉬움"
    "medium" -> "보통"
    else -> "어려움"
}

@Composable
fun SudokuApp() {
    val context = LocalContext.current
    val store = remember { SudokuStore(context) }
    val game = remember { GameState() }

    // Single version counter triggers recomposition after mutations.
    var version by remember { mutableStateOf(0) }
    fun bump() { version++ }

    var helpOn by remember { mutableStateOf(store.loadHelp()) }
    var overlayVisible by remember { mutableStateOf(false) }
    var overlayIcon by remember { mutableStateOf("🎉") }
    var overlayTitle by remember { mutableStateOf("완성!") }
    var overlayText by remember { mutableStateOf("") }
    var overlayBtn by remember { mutableStateOf("새 게임") }

    // Cells that should pulse (unit completion); cleared by animation.
    var pulseCells by remember { mutableStateOf(setOf<Int>()) }
    val pulseAnim = remember { Animatable(0f) }

    // 힌트로 드러난 칸 — 잠깐 금색으로 강조 후 사라짐(-1=없음).
    var hintCell by remember { mutableStateOf(-1) }
    val hintGlow = remember { Animatable(0f) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    fun saveState() {
        if (game.done) return
        val o = JSONObject()
        o.put("solution", JSONArray(game.solution.toList()))
        o.put("puzzle", JSONArray(game.puzzle.toList()))
        o.put("given", JSONArray(game.given.toList()))
        o.put("diff", game.diff)
        o.put("gmode", game.gmode)
        o.put("stage", game.stage)
        o.put("hintsLeft", game.hintsLeft)
        o.put("elapsed", game.elapsed)
        store.saveGame(o)
    }

    fun maxUnlocked(): Int {
        val st = store.loadStats()
        val stageMax = st.optInt("stageMax", 0)
        return min(SudokuEngine.STAGE_MAX, stageMax + 1)
    }

    // chk a unit (row/col/box) fully solved correctly
    fun unitComplete(i: Int): List<Int> {
        val r = i / 9; val c = i % 9
        val out = mutableListOf<Int>()
        fun chk(cells: List<Int>): Boolean {
            val seen = HashSet<Int>()
            for (p in cells) {
                if (game.puzzle[p] == 0 || game.puzzle[p] != game.solution[p]) return false
                seen.add(game.puzzle[p])
            }
            return seen.size == 9
        }
        val row = mutableListOf<Int>(); val col = mutableListOf<Int>(); val box = mutableListOf<Int>()
        val br = (r / 3) * 3; val bc = (c / 3) * 3
        for (k in 0 until 9) { row.add(r * 9 + k); col.add(k * 9 + c) }
        for (y in 0 until 3) for (x in 0 until 3) box.add((br + y) * 9 + (bc + x))
        if (chk(row)) out.addAll(row)
        if (chk(col)) out.addAll(col)
        if (chk(box)) out.addAll(box)
        return out
    }

    fun isComplete(): Boolean {
        for (i in 0 until 81) if (game.puzzle[i] != game.solution[i]) return false
        return true
    }

    fun triggerPulse(cells: List<Int>) {
        if (cells.isEmpty()) return
        pulseCells = cells.toSet()
    }

    fun showOverlay(ic: String, title: String, text: String) {
        overlayIcon = ic; overlayTitle = title; overlayText = text; overlayVisible = true
    }

    fun win() {
        game.done = true
        val best = store.loadBest()
        var rec = ""
        if (!best.containsKey(game.diff) || game.elapsed < best[game.diff]!!) {
            best[game.diff] = game.elapsed
            store.saveBest(best)
            rec = " · 🏆 최단 기록!"
        }
        val st = store.loadStats()
        st.put("cleared", st.optInt("cleared", 0) + 1)
        var extra = ""
        when (game.gmode) {
            "stage" -> {
                if (game.stage > st.optInt("stageMax", 0)) st.put("stageMax", game.stage)
                if (game.stage < SudokuEngine.STAGE_MAX) {
                    extra = "🏆 스테이지 ${game.stage} 클리어!"
                    game.pendingNext = true
                } else {
                    extra = "👑 20스테이지 올클리어! 대단해요!"
                    game.pendingNext = false
                }
            }
            "daily" -> {
                val dailyDone = st.optJSONObject("dailyDone") ?: JSONObject()
                val tk = SudokuEngine.todayStr() + "_" + game.diff
                if (!dailyDone.optBoolean(tk, false)) {
                    dailyDone.put(tk, true)
                    val ystr = SudokuEngine.yesterdayStr()
                    val lastDay = st.optString("lastDailyDay", "")
                    when {
                        lastDay == ystr -> st.put("streak", st.optInt("streak", 0) + 1)
                        lastDay != SudokuEngine.todayStr() -> st.put("streak", 1)
                    }
                    st.put("lastDailyDay", SudokuEngine.todayStr())
                    extra = "🔥 연속 ${st.optInt("streak", 1)}일!"
                } else {
                    extra = "오늘 도전은 이미 완료(연습 플레이)"
                }
                st.put("dailyDone", dailyDone)
            }
        }
        store.saveStats(st)
        store.clearSave()
        val label = if (game.gmode == "stage")
            "스테이지 ${game.stage}/${SudokuEngine.STAGE_MAX}"
        else
            "난이도 ${diffName(game.diff)}"
        val cleared = st.optInt("cleared", 0)
        showOverlay(
            "🎉", "완성!",
            "$label · 시간 ${fmt(game.elapsed)}$rec" +
                (if (extra.isNotEmpty()) "\n$extra" else "") +
                "\n총 클리어 ${cleared}판"
        )
        overlayBtn = if (game.pendingNext) "다음 스테이지 ▶" else "새 게임"
        bump()
    }

    fun place(v: Int) {
        val sel = game.sel
        if (game.done || sel < 0 || game.given[sel]) return
        // Tapping the same number again clears the cell (toggle off).
        if (game.puzzle[sel] == v) {
            game.puzzle[sel] = 0
            saveState(); bump(); return
        }
        game.puzzle[sel] = v
        if (v != game.solution[sel]) {
            // 오답: 도움 모드일 때 빨갛게 표시 + 진동.
            vibrate()
        } else {
            val doneCells = unitComplete(sel)
            if (doneCells.isNotEmpty()) triggerPulse(doneCells)
        }
        saveState(); bump()
        if (isComplete()) win()
    }

    fun hint() {
        if (game.done || game.hintsLeft <= 0) return
        val pool = mutableListOf<Int>()
        for (i in 0 until 81) if (!game.given[i] && game.puzzle[i] != game.solution[i]) pool.add(i)
        if (pool.isEmpty()) return
        val t = if (game.sel >= 0 && pool.contains(game.sel)) game.sel
        else pool[(Math.random() * pool.size).toInt()]
        game.puzzle[t] = game.solution[t]
        game.given[t] = true
        game.sel = t
        game.hintsLeft--
        hintCell = t
        val dc = unitComplete(t)
        if (dc.isNotEmpty()) triggerPulse(dc)
        saveState(); bump()
        if (isComplete()) win()
    }

    fun newGame() {
        game.pendingNext = false
        overlayBtn = "새 게임"
        val pz = SudokuEngine.makePuzzle(game.gmode, game.diff, game.stage)
        game.solution = pz.solution
        game.puzzle = pz.puzzle
        game.given = pz.given
        game.hintsLeft = 3
        game.sel = -1
        game.done = false
        game.elapsed = 0
        hintCell = -1
        overlayVisible = false
        saveState(); bump()
    }

    fun restore(): Boolean {
        val s = store.loadSave() ?: return false
        return try {
            val pArr = s.getJSONArray("puzzle")
            if (pArr.length() != 81) return false
            val solArr = s.getJSONArray("solution")
            val gArr = s.getJSONArray("given")
            for (i in 0 until 81) {
                game.solution[i] = solArr.getInt(i)
                game.puzzle[i] = pArr.getInt(i)
                game.given[i] = gArr.getBoolean(i)
            }
            val d = s.optString("diff", "easy")
            game.diff = if (SudokuEngine.DIFF.containsKey(d)) d else "easy"
            val gm = s.optString("gmode", "normal")
            game.gmode = if (gm == "daily" || gm == "stage") gm else "normal"
            game.stage = maxOf(1, min(SudokuEngine.STAGE_MAX, s.optInt("stage", 1)))
            game.hintsLeft = s.optInt("hintsLeft", 3)
            game.elapsed = s.optInt("elapsed", 0)
            game.done = false
            game.sel = -1
            true
        } catch (_: Exception) {
            false
        }
    }

    // Initial load: restore saved game or start fresh.
    LaunchedEffect(Unit) {
        if (!restore()) newGame() else bump()
    }

    // Timer tick (1s) — runs while not done.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            if (!game.done) {
                game.elapsed++
                bump()
            }
        }
    }

    // Pulse animation driver.
    LaunchedEffect(pulseCells) {
        if (pulseCells.isNotEmpty()) {
            pulseAnim.snapTo(1f)
            pulseAnim.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(600))
            pulseCells = setOf()
        }
    }

    // 힌트 강조 드라이버 — 금색 링/글로우를 펄스 3회 보여준 뒤 ~1.3초 후 사라짐.
    LaunchedEffect(hintCell) {
        if (hintCell >= 0) {
            hintGlow.snapTo(0f)
            // 0→1→0 을 세 번 깜빡(약 1.2초) 후 잔광 정리.
            repeat(3) {
                hintGlow.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(200))
                hintGlow.animateTo(0.25f, animationSpec = androidx.compose.animation.core.tween(200))
            }
            hintGlow.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(150))
            delay(100)
            hintCell = -1
        }
    }

    // -------- derived view values (read `version` so it recomposes) --------
    @Suppress("UNUSED_EXPRESSION")
    version // touch to subscribe

    val selVal = if (game.sel >= 0) game.puzzle[game.sel] else 0
    val counts = IntArray(10)
    for (k in 0 until 81) if (game.puzzle[k] != 0) counts[game.puzzle[k]]++

    val rightStat: String = when (game.gmode) {
        "daily" -> {
            val st = store.loadStats()
            "🔥 연속 ${st.optInt("streak", 0)}일"
        }
        "stage" -> {
            val st = store.loadStats()
            "🏆 ${st.optInt("stageMax", 0)}/${SudokuEngine.STAGE_MAX} 클리어"
        }
        else -> {
            val best = store.loadBest()
            "최단 " + (if (best.containsKey(game.diff)) fmt(best[game.diff]!!) else "—")
        }
    }

    // ============================ UI ============================
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 480.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔢 수도쿠", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
                TogglePill(
                    text = if (helpOn) "🟢 도움 모드" else "⚪ 도움 모드",
                    on = helpOn,
                    onClick = {
                        helpOn = !helpOn
                        store.saveHelp(helpOn)
                    }
                )
            }
            Spacer(Modifier.height(10.dp))

            // Mode row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ModeButton("🎲 일반", game.gmode == "normal", Modifier.weight(1f)) {
                    game.gmode = "normal"; newGame()
                }
                ModeButton("📅 오늘의 도전", game.gmode == "daily", Modifier.weight(1f)) {
                    game.gmode = "daily"; newGame()
                }
                ModeButton("🏆 스테이지", game.gmode == "stage", Modifier.weight(1f)) {
                    game.gmode = "stage"
                    game.stage = min(maxOf(1, game.stage), maxUnlocked())
                    newGame()
                }
            }
            Spacer(Modifier.height(8.dp))

            // Difficulty seg or stage nav
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (game.gmode == "stage") {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegButton("◀", false, Modifier.width(44.dp)) {
                            if (game.stage > 1) { game.stage--; newGame() }
                        }
                        SegButton("스테이지 ${game.stage} / ${SudokuEngine.STAGE_MAX}", false, Modifier.weight(1f)) {}
                        SegButton("▶", false, Modifier.width(44.dp)) {
                            if (game.stage < maxUnlocked()) { game.stage++; newGame() }
                        }
                    }
                } else {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegButton("쉬움", game.diff == "easy", Modifier.weight(1f)) {
                            game.diff = "easy"; newGame()
                        }
                        SegButton("보통", game.diff == "medium", Modifier.weight(1f)) {
                            game.diff = "medium"; newGame()
                        }
                        SegButton("어려움", game.diff == "hard", Modifier.weight(1f)) {
                            game.diff = "hard"; newGame()
                        }
                    }
                }
                NewButton { newGame() }
            }
            Spacer(Modifier.height(8.dp))

            // Stat row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("⏱ ", fmt(game.elapsed))
                Text(rightStat, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMute)
            }
            Spacer(Modifier.height(8.dp))

            // Board
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .border(2.dp, ThickLine, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                    for (r in 0 until 9) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            for (c in 0 until 9) {
                                val i = r * 9 + c
                                CellView(
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    value = game.puzzle[i],
                                    given = game.given[i],
                                    isSel = i == game.sel,
                                    sel = game.sel,
                                    r = r, c = c,
                                    selVal = selVal,
                                    isBad = helpOn && game.puzzle[i] != 0 && !game.given[i] &&
                                        hasConflict(game.puzzle, i),
                                    pulse = if (pulseCells.contains(i)) pulseAnim.value else 0f,
                                    hint = if (i == hintCell) hintGlow.value else 0f,
                                    onClick = { game.sel = i; bump() }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Number pad (9 buttons)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (d in 1..9) {
                    PadButton(
                        modifier = Modifier.weight(1f),
                        digit = d,
                        done = counts[d] >= 9,
                        highlight = selVal != 0 && d == selVal,
                        onClick = { place(d) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Tools row — 힌트만 유지
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ToolButton("💡", "힌트 ${game.hintsLeft}회", false, Modifier.weight(1f)) { hint() }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "칸 선택 → 숫자 입력 · 같은 숫자 다시 누르면 지우기 · 줄·박스 완성 시 반짝!\n도움 모드 ON 시 충돌 숫자가 빨갛게 표시돼요 · 📅 오늘의 도전 · 🏆 스테이지 20판",
                fontSize = 11.sp,
                color = TextMute,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Overlay
        if (overlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF2F0EEFB)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(CardBg)
                        .border(2.dp, ThickLine, RoundedCornerShape(24.dp))
                        .padding(horizontal = 30.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(overlayIcon, fontSize = 42.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(overlayTitle, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        overlayText,
                        fontSize = 13.sp,
                        color = TextMute,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Accent)
                            .clickable {
                                if (game.pendingNext && game.gmode == "stage" && game.stage < SudokuEngine.STAGE_MAX) {
                                    game.stage++
                                }
                                newGame()
                            }
                            .padding(horizontal = 30.dp, vertical = 12.dp)
                    ) {
                        Text(overlayBtn, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                }
            }
        }
    }
}

// ============================== Cell ==============================

// True if cell i's value duplicates another cell in its row/col/box.
private fun hasConflict(grid: IntArray, i: Int): Boolean {
    val v = grid[i]
    if (v == 0) return false
    val r = i / 9; val c = i % 9
    for (k in 0 until 9) {
        val rp = r * 9 + k
        val cp = k * 9 + c
        if (rp != i && grid[rp] == v) return true
        if (cp != i && grid[cp] == v) return true
    }
    val br = (r / 3) * 3; val bc = (c / 3) * 3
    for (y in 0 until 3) for (x in 0 until 3) {
        val p = (br + y) * 9 + (bc + x)
        if (p != i && grid[p] == v) return true
    }
    return false
}

@Composable
private fun CellView(
    modifier: Modifier,
    value: Int,
    given: Boolean,
    isSel: Boolean,
    sel: Int,
    r: Int, c: Int,
    selVal: Int,
    isBad: Boolean,
    pulse: Float,
    hint: Float,
    onClick: () -> Unit
) {
    val baseBg: Color = when {
        isBad -> BadBg
        isSel -> SelBg
        sel >= 0 -> {
            val sr = sel / 9; val sc = sel % 9
            val sameBox = (sr / 3 == r / 3) && (sc / 3 == c / 3)
            val isPeer = sr == r || sc == c || sameBox
            val isSame = selVal != 0 && value == selVal
            when {
                isSame -> SameBg
                isPeer -> PeerBg
                (r / 3 + c / 3) % 2 == 1 -> AltBg
                else -> CardBg
            }
        }
        (r / 3 + c / 3) % 2 == 1 -> AltBg
        else -> CardBg
    }
    var bg = if (pulse > 0f) lerpColor(baseBg, PulseColor, pulse) else baseBg
    // 힌트 칸: 따뜻한 금색 배경으로 한층 더 강조(선택 파란색과 확연히 구분).
    if (hint > 0f) bg = lerpColor(bg, HintBg, hint)

    // Thicker borders between 3x3 boxes (right of col 2,5; bottom of row 2,5).
    val thickRight = c == 2 || c == 5
    val thickBottom = r == 2 || r == 5

    Box(
        modifier = modifier
            .background(bg)
            .drawBehind {
                val rightWpx = if (thickRight) 2.dp.toPx() else 1.dp.toPx()
                val rightCol = if (thickRight) ThickLine else ThinLine
                val bottomWpx = if (thickBottom) 2.dp.toPx() else 1.dp.toPx()
                val bottomCol = if (thickBottom) ThickLine else ThinLine
                // right edge
                drawLine(
                    color = rightCol,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = rightWpx
                )
                // bottom edge
                drawLine(
                    color = bottomCol,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = bottomWpx
                )
                // 힌트 강조: 칸 안쪽에 굵은 금색 링 + 바깥 글로우(깜빡임).
                if (hint > 0f) {
                    val glowW = 7.dp.toPx() * hint
                    if (glowW > 0f) {
                        drawRect(
                            color = HintRing.copy(alpha = 0.28f * hint),
                            topLeft = Offset(-glowW, -glowW),
                            size = androidx.compose.ui.geometry.Size(
                                size.width + glowW * 2f,
                                size.height + glowW * 2f
                            )
                        )
                    }
                    val ringW = 3.dp.toPx()
                    val inset = ringW / 2f
                    drawRect(
                        color = HintRing.copy(alpha = (0.55f + 0.45f * hint).coerceIn(0f, 1f)),
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - ringW,
                            size.height - ringW
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringW)
                    )
                }
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (value != 0) {
            Text(
                value.toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isBad) BadColor else if (given) GivenColor else EditColor
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt
    )
}

// ============================== Buttons ==============================

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(CardBg)
            .border(1.5.dp, ThickLine, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TogglePill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) SameBg else CardBg)
            .border(1.5.dp, if (on) Accent else ThickLine, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (on) Accent else TextMute,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun ModeButton(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Accent else CardBg)
            .border(2.dp, if (on) Accent else ThickLine, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (on) Color.White else TextMute,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SegButton(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Accent else CardBg)
            .border(2.dp, if (on) Accent else ThickLine, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (on) Color.White else TextDark,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NewButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF3FAE74))
            .border(2.dp, Color(0xFF2F8F5D), RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("✨ 새판", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = TextMute, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 12.sp, color = TextDark, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PadButton(
    modifier: Modifier,
    digit: Int,
    done: Boolean,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val border = if (highlight) Accent else ThickLine
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) SelBg else CardBg)
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = !done) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            digit.toString(),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (done) TextMute.copy(alpha = 0.35f) else if (highlight) Accent else TextDark
        )
    }
}

@Composable
private fun ToolButton(icon: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Accent else CardBg)
            .border(2.dp, if (on) Accent else ThickLine, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 15.sp)
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (on) Color.White else TextDark,
                textAlign = TextAlign.Center
            )
        }
    }
}
