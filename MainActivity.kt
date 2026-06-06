package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.business.MatrixCalculationService
import com.example.data.AppDatabase
import com.example.data.SimulationRepository
import com.example.model.CalendarUiState
import com.example.model.RoundRecord
import com.example.model.SimulationSettings
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.composables.*
import com.example.viewmodel.SimulationViewModel
import com.example.viewmodel.SimulationViewModelFactory

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

// ---------- Data classes ----------
data class MatchOdds(val name: String, val yesOdds: Float, val noOdds: Float)
data class MatrixStakes(val yes1: Double, val yes2: Double, val no1: Double, val no2: Double, val parlay: Double, val total: Double)
data class GeneratedMatrix(val match1: MatchOdds, val match2: MatchOdds, val stakes: MatrixStakes, val parlayOdds: Double)

// ---------- Main UI Screen ----------
@Composable
fun SimulationAppScreen(
    settings: SimulationSettings,
    records: List<RoundRecord>,
    onUpdateSettings: (SimulationSettings) -> Unit,
    onSaveRecord: (String, Int, Double) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onReinvestPeriod: () -> Unit
) {
    val calculationService = remember { MatrixCalculationService() }
    
    // Build matches list from settings
    val matches = listOf(
        MatchOdds(settings.match1Name, settings.match1YesOdds, settings.match1NoOdds),
        MatchOdds(settings.match2Name, settings.match2YesOdds, settings.match2NoOdds),
        MatchOdds(settings.match3Name, settings.match3YesOdds, settings.match3NoOdds),
        MatchOdds(settings.match4Name, settings.match4YesOdds, settings.match4NoOdds)
    )
    
    // Generate matrices and expected net map using business logic
    val generatedMatrices = remember(matches, settings.bankroll) {
        calculationService.generateAllMatrices(matches, settings.bankroll)
    }
    
    val expectedNetMap = remember(matches, generatedMatrices) {
        calculationService.computeExpectedNetMap(matches, generatedMatrices)
    }
    
    val matrixStake = settings.bankroll * MatrixConstants.STAKE_PERCENTAGE

    // Analytics calculations
    val totalHistoricalProfit = records.sumOf { it.netUSD }
    val currentBankroll = settings.bankroll + totalHistoricalProfit
    val totalRounds = records.size
    val avgProfit = if (totalRounds > 0) totalHistoricalProfit / totalRounds else 0.0
    val bestDay = records.maxOfOrNull { it.netUSD } ?: 0.0
    val worstDay = records.minOfOrNull { it.netUSD } ?: 0.0
    val roi = if (settings.bankroll > 0) (totalHistoricalProfit / settings.bankroll) * 100 else 0.0

    // Calendar state
    var calendarState by remember { mutableStateOf(CalendarUiState()) }
    var inputNoCount by remember { mutableIntStateOf(2) }
    var inputNetUSD by remember { mutableStateOf("0.00") }

    // Update input when date is selected or record exists
    LaunchedEffect(calendarState.selectedDateStr, expectedNetMap, inputNoCount) {
        if (calendarState.selectedDateStr != null) {
            val existing = records.find { it.dateStr == calendarState.selectedDateStr }
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

            Spacer(modifier = Modifier.height(12.dp))

            // KPI row
            KPIRow(
                settings.periodRounds,
                settings.periodProfit,
                avgProfit,
                bestDay,
                worstDay,
                totalHistoricalProfit,
                roi
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Controls
            ControlsRow(
                baseBankroll = settings.bankroll,
                matrixStake = matrixStake,
                onBankrollChange = { newBase ->
                    onUpdateSettings(settings.copy(bankroll = newBase))
                },
                onGenerate = { /* auto-recompute */ },
                onReinvest = onReinvestPeriod
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Odds input
            OddsInputRow(
                matches = matches,
                onOddsChange = { idx, yes, no ->
                    val newSettings = when (idx) {
                        0 -> settings.copy(match1YesOdds = yes, match1NoOdds = no)
                        1 -> settings.copy(match2YesOdds = yes, match2NoOdds = no)
                        2 -> settings.copy(match3YesOdds = yes, match3NoOdds = no)
                        else -> settings.copy(match4YesOdds = yes, match4NoOdds = no)
                    }
                    onUpdateSettings(newSettings)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Matrices display
            if (generatedMatrices.isNotEmpty()) {
                Text(
                    "Generated Matrices (6 pairs)",
                    color = com.example.ui.theme.GoldAccent,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(generatedMatrices, key = { "${it.match1.name}-${it.match2.name}" }) { matrix ->
                        MatrixCard(matrix)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Calendar
            CalendarCard(
                currentYear = calendarState.currentYear,
                currentMonth = calendarState.currentMonth,
                records = records,
                onPrevMonth = { calendarState = calendarState.previousMonth() },
                onNextMonth = { calendarState = calendarState.nextMonth() },
                onSelectDate = { dateStr ->
                    calendarState = calendarState.selectDate(dateStr)
                }
            )

            // Record edit
            if (calendarState.selectedDateStr != null) {
                RecordEditCard(
                    dateStr = calendarState.selectedDateStr!!,
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
                        onSaveRecord(calendarState.selectedDateStr!!, inputNoCount, net)
                        calendarState = calendarState.clearSelection()
                    },
                    onDelete = {
                        onDeleteRecord(calendarState.selectedDateStr!!)
                        calendarState = calendarState.clearSelection()
                    }
                )
            }

            // Chart
            if (records.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                DailyProfitChart(records)
            }

            // History
            if (records.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HistoryLog(records)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
