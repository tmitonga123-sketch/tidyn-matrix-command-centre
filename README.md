package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppDatabase
import com.example.data.SimulationRepository
import com.example.model.RoundRecord
import com.example.model.SimulationSettings
import com.example.ui.theme.*
import com.example.viewmodel.SimulationViewModel
import com.example.viewmodel.SimulationViewModelFactory
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        val repository = SimulationRepository(database.dao())
        val viewModelFactory = SimulationViewModelFactory(repository)

        enableEdgeToEdge()
        setContent {
            val viewModel: SimulationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = viewModelFactory
            )
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val records by viewModel.records.collectAsStateWithLifecycle()

            MyApplicationTheme {
                SimulationAppScreen(
                    settings = settings,
                    records = records,
                    onUpdateSettings = { viewModel.updateSettings(it) },
                    onSaveRecord = { date, noCount, net -> viewModel.saveRecord(date, noCount, net) },
                    onDeleteRecord = { viewModel.deleteRecord(it) },
                    onReinvestPeriod = { viewModel.reinvestPeriod() }
                )
            }
        }
    }
}

// ---------- Exact 5x5 Linear Solver (works for any odds) ----------
fun solveMatrixExact(mA: MatchOdds, mB: MatchOdds, S: Double): MatrixStakes {
    if (S <= 0.0) return MatrixStakes(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val Y1 = mA.yesOdds.toDouble()
    val N1 = mA.noOdds.toDouble()
    val Y2 = mB.yesOdds.toDouble()
    val N2 = mB.noOdds.toDouble()
    if (Y1 <= 1.0 || N1 <= 1.0 || Y2 <= 1.0 || N2 <= 1.0)
        return MatrixStakes(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    // Equations:
    // 1) a*(Y1-1) + b*(Y2-1) - (c+d+e) = -0.3S
    // 2) a*(Y1-1) + d*(N2-1) - (b+c+e) = -0.3S
    // 3) b*(Y2-1) + c*(N1-1) - (a+d+e) = -0.3S
    // 4) c*(N1-1) + d*(N2-1) + e*(N1*N2-1) - (a+b) = 2.25S
    // 5) a+b+c+d+e = S

    val A = arrayOf(
        doubleArrayOf(Y1-1, Y2-1, -1.0, -1.0, -1.0),
        doubleArrayOf(Y1-1, -1.0, -1.0, N2-1, -1.0),
        doubleArrayOf(-1.0, Y2-1, N1-1, -1.0, -1.0),
        doubleArrayOf(-1.0, -1.0, N1-1, N2-1, N1*N2-1),
        doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)
    )
    val B = doubleArrayOf(-0.3*S, -0.3*S, -0.3*S, 2.25*S, S)

    // Gaussian elimination
    val n = 5
    val M = Array(n) { i -> A[i].clone() }
    val R = B.clone()
    for (i in 0 until n) {
        var maxRow = i
        for (k in i+1 until n) if (abs(M[k][i]) > abs(M[maxRow][i])) maxRow = k
        val tempM = M[i]; M[i] = M[maxRow]; M[maxRow] = tempM
        val tempR = R[i]; R[i] = R[maxRow]; R[maxRow] = tempR
        for (k in i+1 until n) {
            val factor = M[k][i] / M[i][i]
            for (j in i until n) M[k][j] -= factor * M[i][j]
            R[k] -= factor * R[i]
        }
    }
    val X = DoubleArray(n)
    for (i in n-1 downTo 0) {
        var sum = 0.0
        for (j in i+1 until n) sum += M[i][j] * X[j]
        X[i] = (R[i] - sum) / M[i][i]
    }
    var (a,b,c,d,e) = X
    a = max(0.0, a)
    b = max(0.0, b)
    c = max(0.0, c)
    d = max(0.0, d)
    e = max(0.0, e)
    val total = a+b+c+d+e
    if (total > 0 && abs(total - S) > 0.01) {
        val factor = S / total
        a *= factor; b *= factor; c *= factor; d *= factor; e *= factor
    }
    return MatrixStakes(a, b, c, d, e, a+b+c+d+e)
}

// ---------- Data classes ----------
data class MatchOdds(val name: String, val yesOdds: Float, val noOdds: Float)
data class MatrixStakes(val yes1: Double, val yes2: Double, val no1: Double, val no2: Double, val parlay: Double, val total: Double)
data class GeneratedMatrix(val match1: MatchOdds, val match2: MatchOdds, val stakes: MatrixStakes, val parlayOdds: Double)

// ---------- Helper: compute expected net for each No count (0-4) ----------
fun computeExpectedNetMap(matches: List<MatchOdds>, matrices: List<GeneratedMatrix>): Map<Int, Double> {
    if (matrices.isEmpty()) return mapOf(0 to 0.0, 1 to 0.0, 2 to 0.0, 3 to 0.0, 4 to 0.0)
    val combos = listOf(
        0 to listOf(emptySet<Int>()),
        1 to listOf(setOf(0), setOf(1), setOf(2), setOf(3)),
        2 to listOf(setOf(0,1), setOf(0,2), setOf(0,3), setOf(1,2), setOf(1,3), setOf(2,3)),
        3 to listOf(setOf(0,1,2), setOf(0,1,3), setOf(0,2,3), setOf(1,2,3)),
        4 to listOf(setOf(0,1,2,3))
    ).toMap()
    val result = mutableMapOf<Int, Double>()
    for (k in 0..4) {
        val nets = mutableListOf<Double>()
        for (noSet in combos[k] ?: emptyList()) {
            var totalNet = 0.0
            for (m in matrices) {
                val idx1 = matches.indexOfFirst { it.name == m.match1.name }
                val idx2 = matches.indexOfFirst { it.name == m.match2.name }
                val aIsNo = noSet.contains(idx1)
                val bIsNo = noSet.contains(idx2)
                val st = m.stakes
                val Y1 = m.match1.yesOdds.toDouble()
                val N1 = m.match1.noOdds.toDouble()
                val Y2 = m.match2.yesOdds.toDouble()
                val N2 = m.match2.noOdds.toDouble()
                val net = when {
                    !aIsNo && !bIsNo -> st.yes1*(Y1-1) + st.yes2*(Y2-1) - (st.no1+st.no2+st.parlay)
                    !aIsNo && bIsNo -> st.yes1*(Y1-1) + st.no2*(N2-1) - (st.yes2+st.no1+st.parlay)
                    aIsNo && !bIsNo -> st.no1*(N1-1) + st.yes2*(Y2-1) - (st.yes1+st.no2+st.parlay)
                    else -> st.no1*(N1-1) + st.no2*(N2-1) + st.parlay*(N1*N2-1) - (st.yes1+st.yes2)
                }
                totalNet += net
            }
            nets.add(totalNet)
        }
        result[k] = if (nets.isNotEmpty()) nets.average() else 0.0
    }
    return result
}

// ---------- UI Composables ----------

@Composable
fun SimulationAppScreen(
    settings: SimulationSettings,
    records: List<RoundRecord>,
    onUpdateSettings: (SimulationSettings) -> Unit,
    onSaveRecord: (String, Int, Double) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onReinvestPeriod: () -> Unit
) {
    val matches = listOf(
        MatchOdds(settings.match1Name, settings.match1YesOdds, settings.match1NoOdds),
        MatchOdds(settings.match2Name, settings.match2YesOdds, settings.match2NoOdds),
        MatchOdds(settings.match3Name, settings.match3YesOdds, settings.match3NoOdds),
        MatchOdds(settings.match4Name, settings.match4YesOdds, settings.match4NoOdds)
    )
    val matrixStake = settings.bankroll * 0.0278
    val pairs = listOf(
        Pair(matches[0], matches[1]), Pair(matches[0], matches[2]), Pair(matches[0], matches[3]),
        Pair(matches[1], matches[2]), Pair(matches[1], matches[3]), Pair(matches[2], matches[3])
    )
    val generatedMatrices = pairs.map { (m1, m2) ->
        val stakes = solveMatrixExact(m1, m2, matrixStake)
        val parlayOdds = m1.noOdds.toDouble() * m2.noOdds.toDouble()
        GeneratedMatrix(m1, m2, stakes, parlayOdds)
    }
    val expectedNetMap = computeExpectedNetMap(matches, generatedMatrices)

    val totalHistoricalProfit = records.sumOf { it.netUSD }
    val currentBankroll = settings.bankroll + totalHistoricalProfit
    val totalRounds = records.size
    val avgProfit = if (totalRounds > 0) totalHistoricalProfit / totalRounds else 0.0
    val bestDay = records.maxOfOrNull { it.netUSD } ?: 0.0
    val worstDay = records.minOfOrNull { it.netUSD } ?: 0.0
    val roi = if (settings.bankroll > 0) (totalHistoricalProfit / settings.bankroll) * 100 else 0.0

    // Calendar state
    val calendar = java.util.Calendar.getInstance()
    var currentYear by remember { mutableIntStateOf(calendar.get(java.util.Calendar.YEAR)) }
    var currentMonth by remember { mutableIntStateOf(calendar.get(java.util.Calendar.MONTH)) }
    val monthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    var selectedDateStr by remember { mutableStateOf<String?>(null) }
    var inputNoCount by remember { mutableIntStateOf(2) }
    var inputNetUSD by remember { mutableStateOf("0.00") }

    LaunchedEffect(selectedDateStr, expectedNetMap, inputNoCount) {
        if (selectedDateStr != null) {
            val existing = records.find { it.dateStr == selectedDateStr }
            if (existing == null) {
                val expected = expectedNetMap[inputNoCount] ?: 0.0
                inputNetUSD = String.format("%.2f", expected)
            } else {
                inputNoCount = existing.noCount
                inputNetUSD = String.format("%.2f", existing.netUSD)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            HeaderSection(settings.bankroll, currentBankroll)

            // KPI row
            KPIRow(settings.periodRounds, settings.periodProfit, avgProfit, bestDay, worstDay, totalHistoricalProfit, roi)

            Spacer(modifier = Modifier.height(12.dp))

            // Controls: Bankroll input, Generate, Reinvest
            ControlsRow(
                baseBankroll = settings.bankroll,
                matrixStake = matrixStake,
                onBankrollChange = { newBase ->
                    onUpdateSettings(settings.copy(bankroll = newBase))
                },
                onGenerate = { /* no-op, recompute automatically */ },
                onReinvest = onReinvestPeriod
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Odds input for 4 matches
            OddsInputRow(
                matches = matches,
                onOddsChange = { idx, yes, no ->
                    val newMatches = when (idx) {
                        0 -> settings.copy(match1YesOdds = yes, match1NoOdds = no)
                        1 -> settings.copy(match2YesOdds = yes, match2NoOdds = no)
                        2 -> settings.copy(match3YesOdds = yes, match3NoOdds = no)
                        else -> settings.copy(match4YesOdds = yes, match4NoOdds = no)
                    }
                    onUpdateSettings(newMatches)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 6 Matrices display
            if (generatedMatrices.isNotEmpty()) {
                Text("Generated Matrices (6 pairs)", color = GoldAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(generatedMatrices) { matrix ->
                        MatrixCard(matrix)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Calendar + Tracker
            CalendarCard(
                currentYear = currentYear,
                currentMonth = currentMonth,
                records = records,
                onPrevMonth = { if (currentMonth == 0) { currentMonth = 11; currentYear-- } else currentMonth-- },
                onNextMonth = { if (currentMonth == 11) { currentMonth = 0; currentYear++ } else currentMonth++ },
                onSelectDate = { selectedDateStr = it }
            )

            if (selectedDateStr != null) {
                RecordEditCard(
                    dateStr = selectedDateStr!!,
                    noCount = inputNoCount,
                    netUSD = inputNetUSD,
                    onNoCountChange = { newCount ->
                        inputNoCount = newCount
                        val expected = expectedNetMap[newCount] ?: 0.0
                        inputNetUSD = String.format("%.2f", expected)
                    },
                    onNetChange = { inputNetUSD = it },
                    onSave = {
                        val net = inputNetUSD.toDoubleOrNull() ?: 0.0
                        onSaveRecord(selectedDateStr!!, inputNoCount, net)
                        selectedDateStr = null
                    },
                    onDelete = {
                        onDeleteRecord(selectedDateStr!!)
                        selectedDateStr = null
                    }
                )
            }

            // Chart (all-time daily profit)
            if (records.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                DailyProfitChart(records)
            }

            // History log
            if (records.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HistoryLog(records)
            }
        }
    }
}

@Composable
fun HeaderSection(baseBankroll: Double, totalBankroll: Double) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Base bankroll", color = TextMuted, fontSize = 12.sp)
                Text("$${String.format("%.2f", baseBankroll)}", color = GoldAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total bankroll (with profits)", color = TextMuted, fontSize = 12.sp)
                Text("$${String.format("%.2f", totalBankroll)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun KPIRow(
    periodRounds: Int, periodProfit: Double, avgProfit: Double,
    bestDay: Double, worstDay: Double, totalProfit: Double, roi: Double
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KPIItem("Period rounds", periodRounds.toString(), Color.White)
        KPIItem("Period profit", formatMoney(periodProfit), if (periodProfit >= 0) SolidGreen else AlertRed)
        KPIItem("Avg / round", formatMoney(avgProfit), if (avgProfit >= 0) SolidGreen else AlertRed)
        KPIItem("Best / worst", "${formatMoney(bestDay)} / ${formatMoney(worstDay)}", GoldAccent)
        KPIItem("Total profit", formatMoney(totalProfit), if (totalProfit >= 0) SolidGreen else AlertRed)
        KPIItem("Total ROI", "${if (roi >= 0) "+" else ""}${String.format("%.2f", roi)}%", if (roi >= 0) SolidGreen else AlertRed)
    }
}

@Composable
fun KPIItem(label: String, value: String, valueColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Text(label, color = TextMuted, fontSize = 10.sp)
            Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ControlsRow(baseBankroll: Double, matrixStake: Double, onBankrollChange: (Double) -> Unit, onGenerate: () -> Unit, onReinvest: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = if (baseBankroll == 0.0) "" else baseBankroll.toInt().toString(),
            onValueChange = { if (it.isNotEmpty()) onBankrollChange(it.toDoubleOrNull() ?: 0.0) },
            label = { Text("Base bankroll (USD)", color = TextMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GoldAccent,
                unfocusedBorderColor = BorderColor
            )
        )
        Text("Per matrix stake: $${String.format("%.2f", matrixStake)}", color = GoldAccent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Button(onClick = onGenerate, colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)) {
            Icon(Icons.Default.Refresh, contentDescription = "Generate")
            Spacer(Modifier.width(4.dp))
            Text("Generate 6 Matrices")
        }
        Button(onClick = onReinvest, colors = ButtonDefaults.buttonColors(containerColor = DeepOrangeBrown)) {
            Icon(Icons.Default.Add, contentDescription = "Reinvest")
            Spacer(Modifier.width(4.dp))
            Text("Reinvest Period")
        }
    }
}

@Composable
fun OddsInputRow(matches: List<MatchOdds>, onOddsChange: (Int, Float, Float) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Match odds (BTTS Yes / No)", color = GoldAccent, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                matches.forEachIndexed { idx, m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(m.name, color = GoldAccent)
                        OutlinedTextField(
                            value = m.yesOdds.toString(),
                            onValueChange = { newVal ->
                                val yes = newVal.toFloatOrNull() ?: 1.0f
                                onOddsChange(idx, yes, m.noOdds)
                            },
                            label = { Text("Yes", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldAccent)
                        )
                        OutlinedTextField(
                            value = m.noOdds.toString(),
                            onValueChange = { newVal ->
                                val no = newVal.toFloatOrNull() ?: 1.0f
                                onOddsChange(idx, m.yesOdds, no)
                            },
                            label = { Text("No", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MatrixCard(matrix: GeneratedMatrix) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${matrix.match1.name} – ${matrix.match2.name}", fontWeight = FontWeight.Bold)
                Text("No/No parlay: ${String.format("%.2f", matrix.parlayOdds)}x", fontSize = 11.sp, color = GoldAccent)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("✅ YES ${matrix.match1.name}")
                Text("$${String.format("%.2f", matrix.stakes.yes1)}")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("✅ YES ${matrix.match2.name}")
                Text("$${String.format("%.2f", matrix.stakes.yes2)}")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("❌ NO ${matrix.match1.name}")
                Text("$${String.format("%.2f", matrix.stakes.no1)}")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("❌ NO ${matrix.match2.name}")
                Text("$${String.format("%.2f", matrix.stakes.no2)}")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("🎲 PARLAY NO/NO", color = GoldAccent)
                Text("$${String.format("%.2f", matrix.stakes.parlay)}", color = GoldAccent)
            }
        }
    }
}

@Composable
fun CalendarCard(
    currentYear: Int, currentMonth: Int, records: List<RoundRecord>,
    onPrevMonth: () -> Unit, onNextMonth: () -> Unit, onSelectDate: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("📅 Round Tracker (all time)", fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = onPrevMonth) { Icon(Icons.Default.KeyboardArrowLeft, null) }
                    Text("${listOf("January","February","March","April","May","June","July","August","September","October","November","December")[currentMonth]} $currentYear")
                    IconButton(onClick = onNextMonth) { Icon(Icons.Default.KeyboardArrowRight, null) }
                }
            }
            val daysInMonth = java.util.Calendar.getInstance().apply {
                set(currentYear, currentMonth, 1)
            }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val firstDayOfWeek = java.util.Calendar.getInstance().apply {
                set(currentYear, currentMonth, 1)
            }.get(java.util.Calendar.DAY_OF_WEEK)
            val offset = if (firstDayOfWeek == java.util.Calendar.SUNDAY) 6 else firstDayOfWeek - 2
            val days = (0 until offset).map { null } + (1..daysInMonth).map { it }
            Column {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf("M","T","W","T","F","S","S").forEach { Text(it, fontSize = 10.sp, color = TextMuted) }
                }
                days.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            val dateStr = day?.let { "$currentYear-${(currentMonth+1).toString().padStart(2,'0')}-${it.toString().padStart(2,'0')}" }
                            val hasRecord = dateStr != null && records.any { it.dateStr == dateStr }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (hasRecord) GoldAccent.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(if (hasRecord) 1.dp else 0.dp, GoldAccent, RoundedCornerShape(8.dp))
                                    .clickable { dateStr?.let { onSelectDate(it) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(day?.toString() ?: "", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordEditCard(
    dateStr: String,
    noCount: Int,
    netUSD: String,
    onNoCountChange: (Int) -> Unit,
    onNetChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(dateStr, color = GoldAccent, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = noCount.toString(),
                    onValueChange = { it.toIntOrNull()?.let { onNoCountChange(it.coerceIn(0,4)) } },
                    label = { Text("BTTS No count (0-4)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = netUSD,
                    onValueChange = onNetChange,
                    label = { Text("Net P/L (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = SolidGreen)) {
                    Icon(Icons.Default.Add, null)
                    Text("Save")
                }
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = AlertRed)) {
                    Icon(Icons.Default.Delete, null)
                    Text("Delete")
                }
            }
            Text("* Auto‑calculated from No count based on current matrix stakes", fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
fun DailyProfitChart(records: List<RoundRecord>) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📈 Daily profit evolution (all time)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val sorted = records.sortedBy { it.dateStr }
            val profits = sorted.map { it.netUSD }
            val maxProfit = profits.maxOrNull() ?: 1.0
            val minProfit = profits.minOrNull() ?: -1.0
            val range = maxProfit - minProfit
            val height = 200.dp
            val width = 300.dp
            Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
                val stepX = size.width / (profits.size.coerceAtLeast(2) - 1)
                val points = profits.mapIndexed { i, p ->
                    val y = if (range > 0) size.height * (1 - (p - minProfit) / range) else size.height / 2
                    Offset(stepX * i, y)
                }
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                    }
                    drawPath(path, GoldAccent, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // optional: draw circles
                points.forEach { point ->
                    drawCircle(color = GoldAccent, radius = 4f, center = point)
                }
            }
        }
    }
}

@Composable
fun HistoryLog(records: List<RoundRecord>) {
    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📋 Complete round history (audit trail)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(records.sortedByDescending { it.dateStr }) { rec ->
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(rec.dateStr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("${rec.noCount} No's →", fontSize = 12.sp)
                        Text(formatMoney(rec.netUSD), color = if (rec.netUSD >= 0) SolidGreen else AlertRed, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatMoney(value: Double) = String.format("$%.2f", value)
