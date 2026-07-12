package edu.ccit.webvpn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.ccit.webvpn.core.academic.EvaluationAnswer
import edu.ccit.webvpn.core.academic.EvaluationBatch
import edu.ccit.webvpn.core.academic.EvaluationCourse
import edu.ccit.webvpn.core.academic.EvaluationForm
import edu.ccit.webvpn.core.academic.SelectedCourse
import edu.ccit.webvpn.core.ui.WebVpnCard
import edu.ccit.webvpn.core.ui.WebVpnColors
import edu.ccit.webvpn.core.ui.WebVpnPrimaryButton
import edu.ccit.webvpn.core.ui.webVpnTextFieldColors

@Composable
fun CourseSelectionResultsScreen(
    state: AcademicUiState,
    onSelectTerm: (String) -> Unit,
    onQuery: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state.courseSelectionTerms.isEmpty() && !state.loadingCourseSelection) onQuery()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "selection_filters", contentType = "filters") {
            SelectionFilters(state, onSelectTerm, onQuery)
        }
        when {
            state.loadingCourseSelection -> item(key = "selection_loading", contentType = "status") {
                NativeLoading("正在加载选课结果…")
            }
            state.selectedCourses.isEmpty() -> item(key = "selection_empty", contentType = "status") {
                NativeEmpty("当前学期没有选课结果")
            }
            else -> items(
                items = state.selectedCourses,
                key = { "${it.sequence}:${it.courseCode}" },
                contentType = { "selected_course" },
            ) { course ->
                SelectedCourseCard(course)
            }
        }
        item(key = "selection_bottom", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SelectionFilters(
    state: AcademicUiState,
    onSelectTerm: (String) -> Unit,
    onQuery: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = state.courseSelectionTerms
        .firstOrNull { it.value == state.selectedCourseSelectionTerm }
        ?.label
        ?: "选择学年学期"
    WebVpnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按学期查看已选课程", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.courseSelectionTerms.isNotEmpty(),
                ) {
                    Text(label, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.courseSelectionTerms.filter { it.value.isNotBlank() }.forEach { term ->
                        DropdownMenuItem(
                            text = { Text(term.label) },
                            onClick = {
                                expanded = false
                                onSelectTerm(term.value)
                            },
                        )
                    }
                }
            }
            WebVpnPrimaryButton(
                onClick = onQuery,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loadingCourseSelection,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查询选课结果")
            }
        }
    }
}

@Composable
private fun SelectedCourseCard(course: SelectedCourse) {
    WebVpnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(course.courseName, style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(course.courseCode, course.teacher).filter(String::isNotBlank).joinToString(" · "),
                color = WebVpnColors.InkMuted,
            )
            Text(
                listOf(
                    "学分 ${course.credit.ifBlank { "—" }}",
                    "学时 ${course.totalHours.ifBlank { "—" }}",
                    course.courseAttribute,
                    course.courseNature,
                ).filter(String::isNotBlank).joinToString("  ·  "),
                color = WebVpnColors.InkMuted,
            )
        }
    }
}

@Composable
fun StudentEvaluationScreen(
    state: AcademicUiState,
    onLoadBatches: () -> Unit,
    onOpenBatch: (String) -> Unit,
    onCloseBatch: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onCloseForm: () -> Unit,
    onSave: (List<EvaluationAnswer>, String, Boolean) -> Unit,
) {
    val navController = rememberNavController()
    LaunchedEffect(Unit) {
        if (state.evaluationBatches.isEmpty() && !state.loadingEvaluation) onLoadBatches()
    }
    NavHost(
        navController = navController,
        startDestination = EvaluationBatchesRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(EvaluationBatchesRoute) {
            EvaluationOverview(
                state = state,
                showingCourses = false,
                onLoadBatches = onLoadBatches,
                onOpenBatch = { path ->
                    onOpenBatch(path)
                    navController.navigate(EvaluationCoursesRoute) { launchSingleTop = true }
                },
                onCloseBatch = onCloseBatch,
                onOpenCourse = onOpenCourse,
            )
        }
        composable(EvaluationCoursesRoute) {
            EvaluationOverview(
                state = state,
                showingCourses = true,
                onLoadBatches = onLoadBatches,
                onOpenBatch = onOpenBatch,
                onCloseBatch = {
                    onCloseBatch()
                    navController.popBackStack()
                },
                onOpenCourse = { path ->
                    onOpenCourse(path)
                    navController.navigate(EvaluationFormRoute) { launchSingleTop = true }
                },
            )
        }
        composable(EvaluationFormRoute) {
            var formWasLoaded by remember { mutableStateOf(false) }
            val form = state.evaluationForm
            LaunchedEffect(form, state.savingEvaluation) {
                if (form != null) formWasLoaded = true
                if (form == null && formWasLoaded && !state.savingEvaluation) {
                    navController.popBackStack()
                }
            }
            if (form == null) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.loadingEvaluation) {
                        NativeLoading("正在加载课程评价表…")
                    } else {
                        NativeEmpty("课程评价表加载失败，请返回课程列表后重试")
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("返回课程列表")
                        }
                    }
                }
            } else {
                EvaluationFormScreen(
                    form = form,
                    saving = state.savingEvaluation,
                    onBack = {
                        onCloseForm()
                        navController.popBackStack()
                    },
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun EvaluationOverview(
    state: AcademicUiState,
    showingCourses: Boolean,
    onLoadBatches: () -> Unit,
    onOpenBatch: (String) -> Unit,
    onCloseBatch: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "evaluation_toolbar", contentType = "toolbar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showingCourses) {
                    IconButton(onClick = onCloseBatch) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回评价批次")
                    }
                }
                Text(
                    if (showingCourses) "评价课程" else "评价批次",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        state.selectedEvaluationBatchPath?.let(onOpenBatch) ?: onLoadBatches()
                    },
                    enabled = !state.loadingEvaluation,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }
        if (state.loadingEvaluation) {
            item(key = "evaluation_loading", contentType = "status") {
                NativeLoading("正在加载学生评价…")
            }
        } else if (showingCourses) {
            if (state.evaluationCourses.isEmpty()) {
                item(key = "evaluation_course_empty", contentType = "status") {
                    NativeEmpty("当前批次没有评价课程")
                }
            } else {
                items(
                    items = state.evaluationCourses,
                    key = { "${it.sequence}:${it.courseCode}:${it.teacher}" },
                    contentType = { "evaluation_course" },
                ) { course -> EvaluationCourseCard(course, onOpenCourse) }
            }
        } else if (state.evaluationBatches.isEmpty()) {
            item(key = "evaluation_batch_empty", contentType = "status") {
                NativeEmpty("当前没有开放的评价批次")
            }
        } else {
            items(
                items = state.evaluationBatches,
                key = { "${it.sequence}:${it.courseListPath}" },
                contentType = { "evaluation_batch" },
            ) { batch -> EvaluationBatchCard(batch, onOpenBatch) }
        }
        item(key = "evaluation_bottom", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

private const val EvaluationBatchesRoute = "evaluation_batches"
private const val EvaluationCoursesRoute = "evaluation_courses"
private const val EvaluationFormRoute = "evaluation_form"

@Composable
private fun EvaluationBatchCard(batch: EvaluationBatch, onOpen: (String) -> Unit) {
    WebVpnCard(
        Modifier.fillMaxWidth().clickable { onOpen(batch.courseListPath) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(batch.name, style = MaterialTheme.typography.titleMedium)
            Text("${batch.semester} · ${batch.category}", color = WebVpnColors.InkMuted)
            Text("开放时间：${batch.startDate} 至 ${batch.endDate}", color = WebVpnColors.InkMuted)
        }
    }
}

@Composable
private fun EvaluationCourseCard(course: EvaluationCourse, onOpen: (String) -> Unit) {
    WebVpnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(course.courseName, style = MaterialTheme.typography.titleMedium)
                    Text("${course.courseCode} · ${course.teacher}", color = WebVpnColors.InkMuted)
                }
                Text(
                    when {
                        course.submitted -> "已提交"
                        course.evaluated -> "已保存"
                        else -> "待评价"
                    },
                    color = if (course.submitted) WebVpnColors.Success else WebVpnColors.Brown,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                listOf(course.category, course.teachingHours.takeIf(String::isNotBlank)?.let { "讲课 $it 学时" })
                    .filterNotNull().joinToString(" · "),
                color = WebVpnColors.InkMuted,
            )
            OutlinedButton(onClick = { onOpen(course.formPath) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (course.submitted) "查看评价" else "填写评价")
            }
        }
    }
}

@Composable
private fun EvaluationFormScreen(
    form: EvaluationForm,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (List<EvaluationAnswer>, String, Boolean) -> Unit,
) {
    val answers = remember(form) {
        mutableStateMapOf<String, String>().apply {
            form.questions.forEach { question ->
                question.options.firstOrNull { it.selected }?.let { put(question.id, it.id) }
            }
        }
    }
    var suggestion by remember(form) { mutableStateOf(form.suggestion) }
    val complete = form.questions.all { !answers[it.id].isNullOrBlank() }
    val selectedIndices = form.questions.mapNotNull { question ->
        question.options.indexOfFirst { it.id == answers[question.id] }.takeIf { it >= 0 }
    }.toSet()
    val rejectSameChoice = !form.readOnly &&
        form.hiddenFields.any { it.name == "isxtjg" && it.value == "1" } &&
        form.questions.size > 1 && complete && selectedIndices.size == 1

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "evaluation_form_header", contentType = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回课程列表")
                }
                Column(Modifier.weight(1f)) {
                    Text(form.courseName.ifBlank { "课程评价" }, style = MaterialTheme.typography.titleMedium)
                    if (form.category.isNotBlank()) Text(form.category, color = WebVpnColors.InkMuted)
                }
                if (form.readOnly) Text("只读", color = WebVpnColors.Success, fontWeight = FontWeight.Bold)
            }
        }
        items(
            items = form.questions,
            key = { it.id },
            contentType = { "evaluation_question" },
        ) { question ->
            WebVpnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(question.title, style = MaterialTheme.typography.titleMedium)
                    question.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !form.readOnly && !saving) {
                                    answers[question.id] = option.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = answers[question.id] == option.id,
                                onClick = { answers[question.id] = option.id },
                                enabled = !form.readOnly && !saving,
                            )
                            Text(option.label.ifBlank { option.score })
                        }
                    }
                }
            }
        }
        form.suggestionField?.let {
            item(key = "evaluation_suggestion", contentType = "suggestion") {
                OutlinedTextField(
                    value = suggestion,
                    onValueChange = { suggestion = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("学生建议") },
                    enabled = !form.readOnly && !saving,
                    minLines = 3,
                    colors = webVpnTextFieldColors(),
                )
            }
        }
        if (rejectSameChoice) {
            item(key = "evaluation_same_warning", contentType = "status") {
                Text("所有指标不能选择同一档，请调整至少一项。", color = MaterialTheme.colorScheme.error)
            }
        }
        if (!form.readOnly) {
            item(key = "evaluation_actions", contentType = "actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            onSave(answers.map { EvaluationAnswer(it.key, it.value) }, suggestion, false)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = complete && !rejectSameChoice && !saving,
                    ) {
                        Text("保存")
                    }
                    WebVpnPrimaryButton(
                        onClick = {
                            onSave(answers.map { EvaluationAnswer(it.key, it.value) }, suggestion, true)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = complete && !rejectSameChoice && !saving,
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("提交")
                        }
                    }
                }
            }
        }
        item(key = "evaluation_form_bottom", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NativeLoading(message: String) {
    WebVpnCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(message, color = WebVpnColors.InkMuted)
        }
    }
}

@Composable
private fun NativeEmpty(message: String) {
    WebVpnCard(Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(20.dp), color = WebVpnColors.InkMuted)
    }
}
