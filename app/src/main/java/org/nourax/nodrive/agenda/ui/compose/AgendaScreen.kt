/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package org.nourax.nodrive.agenda.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.StatusKeys
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AgendaRow
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.CategoryFilter
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.SourceState
import org.nourax.nodrive.agenda.ui.AgendaPage
import org.nourax.nodrive.agenda.ui.AgendaTexts
import org.nourax.nodrive.agenda.ui.AgendaViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields

/** Opens a page of the account's own server, signed in, inside the app. */
fun interface OpenUrl {
    fun open(url: String, title: String)
}

/** The NoAgenda screen: Day, Week and Month lists, the Sources screen and the one-time question. */
@Composable
fun AgendaScreen(vm: AgendaViewModel, baseUrl: String, openUrl: OpenUrl) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    val texts = remember(context) { AgendaTexts(context) }
    BackHandler(enabled = ui.page == AgendaPage.SOURCES) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (ui.page == AgendaPage.SOURCES) {
            SourcesScreen(vm, ui.snapshot, texts)
        } else {
            MainScreen(vm, ui, texts, baseUrl, openUrl)
        }
    }
    val build = ui.snapshot?.build
    if (ui.page == AgendaPage.MAIN && build != null && build.needsPersonalQuestion) {
        PersonalCalendarsDialog(
            calendars = build.ownCalendars,
            onSave = { personal -> vm.answerPersonal(personal, build.ownCalendars.map { it.uri }, skipped = false) },
            onSkip = { vm.answerPersonal(emptySet(), emptyList(), skipped = true) }
        )
    }
}

/** What the screen says about the sources that did not answer (design 8, plan 1.4). */
data class Banner(val text: String, val openHome: Home? = null)

fun banners(snapshot: AgendaSnapshot, texts: AgendaTexts, hasItems: Boolean, context: android.content.Context): List<Banner> {
    val out = ArrayList<Banner>()
    val build = snapshot.build
    val zone = snapshot.zone
    fun at(ms: Long) = texts.clock(ms, zone)

    val user = snapshot.status(StatusKeys.USER)
    val calendar = snapshot.status(StatusKeys.CALENDAR)
    if (user.state == SourceState.NEEDS_LOGIN || calendar.state == SourceState.NEEDS_LOGIN) {
        out += Banner(context.getString(R.string.nodrive_agenda_sign_in_again))
    }
    val relevant = buildList {
        add(calendar)
        if (!build.ganttUnavailable) add(snapshot.status(StatusKeys.GANTT))
        if (!build.boardUnavailable) add(snapshot.status(StatusKeys.BOARD))
    }
    val failedStates = setOf(SourceState.CACHED, SourceState.FAILED)
    val allOffline = relevant.all { it.state in failedStates && it.httpStatus == 0 }
    if (allOffline) {
        val saved = relevant.maxOf { it.savedAtMs }
        out += Banner(
            if (saved > 0) context.getString(R.string.nodrive_agenda_offline_saved, at(saved)) else context.getString(R.string.nodrive_agenda_offline_nothing)
        )
    } else {
        fun failed(name: Int, status: org.nourax.nodrive.agenda.model.SourceStatus) {
            if (status.state !in failedStates) return
            val label = context.getString(name)
            out += Banner(
                if (status.savedAtMs > 0) context.getString(R.string.nodrive_agenda_cannot_reach, label, at(status.savedAtMs))
                else context.getString(R.string.nodrive_agenda_cannot_reach_nothing, label)
            )
        }
        failed(R.string.nodrive_agenda_home_calendar, calendar)
        if (!build.ganttUnavailable) failed(R.string.nodrive_agenda_home_gantt, snapshot.status(StatusKeys.GANTT))
        if (!build.boardUnavailable) failed(R.string.nodrive_agenda_home_board, snapshot.status(StatusKeys.BOARD))
    }
    val gantt = snapshot.status(StatusKeys.GANTT)
    val board = snapshot.status(StatusKeys.BOARD)
    if (gantt.state == SourceState.REFUSED) {
        out += Banner(context.getString(R.string.nodrive_agenda_refused, context.getString(R.string.nodrive_agenda_home_gantt)), Home.GANTT)
    }
    if (board.state == SourceState.REFUSED) {
        out += Banner(context.getString(R.string.nodrive_agenda_refused, context.getString(R.string.nodrive_agenda_home_board)), Home.BOARD)
    }
    if (build.ganttReadOnlyByGuard) out += Banner(context.getString(R.string.nodrive_agenda_guard_gantt), Home.GANTT)
    if (build.boardReadOnlyByGuard) out += Banner(context.getString(R.string.nodrive_agenda_guard_board), Home.BOARD)
    // Not linked: the empty state says it when nothing else is shown; otherwise it is a banner.
    if (build.ganttNotLinked && hasItems) out += Banner(context.getString(R.string.nodrive_agenda_not_linked))
    return out
}

@Composable
private fun MainScreen(vm: AgendaViewModel, ui: org.nourax.nodrive.agenda.ui.AgendaUiState, texts: AgendaTexts, baseUrl: String, openUrl: OpenUrl) {
    val context = LocalContext.current
    val snapshot = ui.snapshot
    val firstDay = remember { WeekFields.of(context.resources.configuration.locales[0]).firstDayOfWeek }
    val rows = remember(snapshot, ui.view, firstDay) {
        if (snapshot == null) emptyList() else AgendaFilter.rows(snapshot.build, ui.view, snapshot.today, snapshot.zone, firstDay)
    }
    val hasItems = AgendaFilter.hasItems(rows)
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Header(vm, ui, texts, firstDay)
        if (snapshot != null) {
            for (banner in banners(snapshot, texts, hasItems, context)) BannerView(banner, baseUrl, openUrl)
        }
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (snapshot == null) {
                EmptyState(stringResource(R.string.nodrive_agenda_loading))
            } else {
                AgendaList(vm, ui, snapshot, rows, hasItems, texts, listState, firstDay)
            }
        }
    }
    val selected = ui.selectedKey?.let { key -> snapshot?.build?.items?.firstOrNull { it.key == key } }
    if (selected != null && snapshot != null) {
        DetailSheet(selected, snapshot, texts, onDismiss = { vm.select(null) }) { item ->
            vm.select(null)
            openUrl.open(org.nourax.nodrive.agenda.ui.AgendaLinks.itemUrl(item, baseUrl, snapshot.zone), openLabel(context, item))
        }
    }
}

fun openLabel(context: android.content.Context, item: AgendaItem): String = context.getString(openLabelRes(item.home))

fun openLabelRes(home: Home): Int = when (home) {
    Home.CALENDAR -> R.string.nodrive_agenda_open_calendar
    Home.TASKS -> R.string.nodrive_agenda_open_tasks
    Home.GANTT -> R.string.nodrive_agenda_open_gantt
    Home.BOARD -> R.string.nodrive_agenda_open_board
}

@Composable
private fun BannerView(banner: Banner, baseUrl: String, openUrl: OpenUrl) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("agenda_banner")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(banner.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            banner.openHome?.let { home ->
                TextButton(onClick = { openUrl.open(org.nourax.nodrive.agenda.ui.AgendaLinks.appUrl(home, baseUrl), context.getString(openLabelRes(home))) }) {
                    Text(stringResource(openLabelRes(home)))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("agenda_empty"))
    }
}

@Composable
private fun AgendaList(
    vm: AgendaViewModel,
    ui: org.nourax.nodrive.agenda.ui.AgendaUiState,
    snapshot: AgendaSnapshot,
    rows: List<AgendaRow>,
    hasItems: Boolean,
    texts: AgendaTexts,
    listState: LazyListState,
    firstDay: DayOfWeek
) {
    val build = snapshot.build
    var jumpTo by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(jumpTo, rows) {
        val target = jumpTo ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is AgendaRow.DayHeader && !it.date.isBefore(target) }
        if (index >= 0) listState.animateScrollToItem(index + 1)
        jumpTo = null
    }
    LazyColumn(Modifier.fillMaxSize().testTag("agenda_list"), state = listState) {
        if (ui.view.view == AgendaView.MONTH) {
            item(key = "month-grid") {
                MonthGrid(ui.view.date, snapshot.today, AgendaFilter.busyDays(build, ui.view, snapshot.zone), firstDay) { day ->
                    vm.setDate(day)
                    jumpTo = day
                }
            }
        }
        if (!hasItems) {
            item(key = "empty") {
                val neverLoaded = snapshot.statuses.values.none { it.savedAtMs > 0 }
                val message = when {
                    ui.refreshing && neverLoaded -> stringResource(R.string.nodrive_agenda_loading)
                    build.ganttNotLinked -> stringResource(R.string.nodrive_agenda_not_linked)
                    ui.view.view == AgendaView.WEEK -> stringResource(R.string.nodrive_agenda_nothing_week)
                    ui.view.view == AgendaView.MONTH -> stringResource(R.string.nodrive_agenda_nothing_month)
                    else -> stringResource(R.string.nodrive_agenda_nothing_day)
                }
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("agenda_empty"))
                }
            }
        }
        // Without any item the Week and Month lists would only repeat "Nothing planned" for every day.
        val shownRows = if (hasItems || ui.view.view == AgendaView.DAY) rows else emptyList()
        itemsIndexed(shownRows, key = { index, row -> rowKey(index, row) }) { _, row ->
            when (row) {
                is AgendaRow.DayHeader -> DayHeaderRow(row, texts)
                is AgendaRow.Group -> GroupHeaderRow(row, texts, ui.view.showAllTeam, vm::toggleNoDate, vm::toggleShowAllTeam)
                is AgendaRow.Quiet -> QuietRow(row)
                is AgendaRow.Item -> {
                    AgendaItemRow(row, texts) { vm.select(row.item.key) }
                    HorizontalDivider(Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

private fun rowKey(index: Int, row: AgendaRow): String = when (row) {
    is AgendaRow.DayHeader -> "day-${row.date}"
    is AgendaRow.Group -> "group-${index}-${row.kind}"
    is AgendaRow.Quiet -> "quiet-$index"
    is AgendaRow.Item -> "item-${row.day}-${row.item.key}-${row.inTeam}"
}

@Composable
private fun Header(vm: AgendaViewModel, ui: org.nourax.nodrive.agenda.ui.AgendaUiState, texts: AgendaTexts, firstDay: DayOfWeek) {
    val state = ui.view
    var showPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::today, modifier = Modifier.testTag("agenda_today")) { Text(stringResource(R.string.nodrive_agenda_today)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::refresh, modifier = Modifier.testTag("agenda_refresh")) { Text(stringResource(R.string.nodrive_agenda_refresh)) }
            TextButton(onClick = vm::openSources, modifier = Modifier.testTag("agenda_sources")) { Text(stringResource(R.string.nodrive_agenda_sources)) }
        }
        PrimaryTabRow(selectedTabIndex = state.view.ordinal) {
            AgendaView.entries.forEach { view ->
                Tab(
                    selected = state.view == view,
                    onClick = { vm.setView(view) },
                    text = { Text(stringResource(viewLabel(view))) },
                    modifier = Modifier.testTag("agenda_tab_${view.name.lowercase()}")
                )
            }
        }
        val range = AgendaFilter.range(state, firstDay)
        val title = when (state.view) {
            AgendaView.DAY -> texts.fullDate(state.date)
            AgendaView.WEEK -> texts.weekTitle(range.first, range.second)
            AgendaView.MONTH -> texts.monthTitle(state.date)
        }
        val previousText = stringResource(R.string.nodrive_agenda_previous)
        val nextText = stringResource(R.string.nodrive_agenda_next)
        val chooseText = stringResource(R.string.nodrive_agenda_choose_date)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.shift(-1) }, modifier = Modifier.testTag("agenda_previous").semantics { contentDescription = previousText }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f).testTag("agenda_date").semantics { contentDescription = "$chooseText: $title" }) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { vm.shift(1) }, modifier = Modifier.testTag("agenda_next").semantics { contentDescription = nextText }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            CategoryFilter.entries.forEachIndexed { index, category ->
                SegmentedButton(
                    selected = state.category == category,
                    onClick = { vm.setCategory(category) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CategoryFilter.entries.size),
                    modifier = Modifier.testTag("agenda_category_${category.name.lowercase()}")
                ) { Text(stringResource(categoryLabel(category))) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.chipMe, onClick = vm::toggleMe, label = { Text(stringResource(R.string.nodrive_agenda_chip_me)) }, modifier = Modifier.testTag("agenda_chip_me"))
            FilterChip(selected = state.chipAgents, onClick = vm::toggleAgents, label = { Text(stringResource(R.string.nodrive_agenda_chip_agents)) }, modifier = Modifier.testTag("agenda_chip_agents"))
            FilterChip(selected = state.chipTeam, onClick = vm::toggleTeam, label = { Text(stringResource(R.string.nodrive_agenda_chip_team)) }, modifier = Modifier.testTag("agenda_chip_team"))
        }
        ui.snapshot?.let { snapshot ->
            if (snapshot.savedAtMs > 0) {
                Text(
                    stringResource(R.string.nodrive_agenda_updated, texts.clock(snapshot.savedAtMs, snapshot.zone)),
                    Modifier.padding(horizontal = 16.dp).testTag("agenda_updated"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showPicker = false
                }) { Text(stringResource(R.string.nodrive_agenda_ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.nodrive_agenda_cancel)) } }
        ) { DatePicker(state = pickerState) }
    }
}

private fun viewLabel(view: AgendaView): Int = when (view) {
    AgendaView.DAY -> R.string.nodrive_agenda_view_day
    AgendaView.WEEK -> R.string.nodrive_agenda_view_week
    AgendaView.MONTH -> R.string.nodrive_agenda_view_month
}

private fun categoryLabel(category: CategoryFilter): Int = when (category) {
    CategoryFilter.ALL -> R.string.nodrive_agenda_category_all
    CategoryFilter.WORK -> R.string.nodrive_agenda_category_work
    CategoryFilter.PERSONAL -> R.string.nodrive_agenda_category_personal
}
