package edu.ccit.webvpn

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import edu.ccit.webvpn.core.academic.CourseGrade
import edu.ccit.webvpn.core.ui.CcitCard
import edu.ccit.webvpn.core.ui.CcitSkeletonBlock
import edu.ccit.webvpn.core.ui.CcitOutlinedButton
import edu.ccit.webvpn.core.ui.CcitSelectField
import edu.ccit.webvpn.core.ui.CcitColors
import edu.ccit.webvpn.core.ui.CcitPrimaryButton
import edu.ccit.webvpn.core.ui.ccitTextFieldColors

@Composable
fun AcademicSection(
    state: AcademicUiState,
    onRefreshCaptcha: () -> Unit,
    onLogin: (String, String, String, Boolean) -> Unit,
    onSelectSavedAccount: (String) -> Unit,
    onForgetSavedAccount: (String) -> Unit,
    onUseManualCredentials: () -> Unit,
    onSelectTerm: (String) -> Unit,
    onBestOnlyChanged: (Boolean) -> Unit,
    onQueryGrades: () -> Unit,
) {
    CcitCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, contentDescription = null, tint = CcitColors.Brown)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("教务系统登录", style = MaterialTheme.typography.titleLarge)
                    Text("教学一体化服务平台", color = CcitColors.InkMuted)
                }
            }

            when {
                state.initializing -> AcademicLoading("正在恢复教务系统登录…")
                !state.loggedIn -> AcademicLoginForm(
                    state,
                    onRefreshCaptcha,
                    onLogin,
                    onSelectSavedAccount,
                    onForgetSavedAccount,
                    onUseManualCredentials,
                )
                else -> AcademicGradeFilters(
                    state = state,
                    onSelectTerm = onSelectTerm,
                    onBestOnlyChanged = onBestOnlyChanged,
                    onQueryGrades = onQueryGrades,
                )
            }
        }
    }

    if (state.loggedIn) {
        when {
            state.loadingGrades -> CcitCard(modifier = Modifier.fillMaxWidth()) {
                AcademicGradeSkeleton("正在查询成绩…")
            }
            state.grades.isEmpty() -> CcitCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "当前筛选条件下没有成绩记录。",
                    modifier = Modifier.padding(18.dp),
                    color = CcitColors.InkMuted,
                )
            }
            else -> state.grades.forEach { grade -> GradeCard(grade) }
        }
    }
}

@Composable
fun AcademicGradesScreen(
    state: AcademicUiState,
    onSelectTerm: (String) -> Unit,
    onBestOnlyChanged: (Boolean) -> Unit,
    onQueryGrades: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "grade_filters", contentType = "grade_filters") {
            CcitCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.School, contentDescription = null, tint = CcitColors.Brown)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("成绩查询", style = MaterialTheme.typography.titleLarge)
                            Text("按学期筛选课程成绩", color = CcitColors.InkMuted)
                        }
                    }
                    AcademicGradeFilters(state, onSelectTerm, onBestOnlyChanged, onQueryGrades)
                }
            }
        }
        when {
            state.loadingGrades -> item(key = "grade_loading", contentType = "grade_status") {
                CcitCard(modifier = Modifier.fillMaxWidth()) {
                    AcademicGradeSkeleton("正在查询成绩…")
                }
            }
            state.grades.isEmpty() -> item(key = "grade_empty", contentType = "grade_status") {
                CcitCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "当前筛选条件下没有成绩记录。",
                        modifier = Modifier.padding(18.dp),
                        color = CcitColors.InkMuted,
                    )
                }
            }
            else -> itemsIndexed(
                items = state.grades,
                key = { index, grade ->
                    "${grade.sequence}:${grade.semester}:${grade.courseCode}:$index"
                },
                contentType = { _, _ -> "grade" },
            ) { _, grade ->
                GradeCard(grade)
            }
        }
        item(key = "grade_bottom_space", contentType = "spacer") {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AcademicLoginForm(
    state: AcademicUiState,
    onRefreshCaptcha: () -> Unit,
    onLogin: (String, String, String, Boolean) -> Unit,
    onSelectSavedAccount: (String) -> Unit,
    onForgetSavedAccount: (String) -> Unit,
    onUseManualCredentials: () -> Unit,
) {
    var username by remember { mutableStateOf(state.defaultUsername) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var captchaCode by remember(state.captcha) { mutableStateOf("") }
    var captchaEdited by remember(state.captcha) { mutableStateOf(false) }
    val usingSavedPassword = state.selectedSavedUsername != null

    LaunchedEffect(state.defaultUsername, state.selectedSavedUsername) {
        if (state.defaultUsername.isNotBlank()) username = state.defaultUsername
        if (usingSavedPassword) {
            password = ""
            rememberPassword = false
        }
    }

    LaunchedEffect(state.captcha, state.recognizedCaptchaCode) {
        if (!captchaEdited && state.recognizedCaptchaCode.isNotBlank()) {
            captchaCode = state.recognizedCaptchaCode
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    Text(
        "WebVPN 已连接，请登录学生端。",
        color = CcitColors.InkMuted,
    )
    if (state.savedAccounts.isNotEmpty()) {
        Text("已保存教务账号", style = MaterialTheme.typography.titleMedium)
        state.savedAccounts.forEach { account ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CcitOutlinedButton(
                    onClick = { onSelectSavedAccount(account.username) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (state.selectedSavedUsername == account.username) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Person
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(account.username)
                }
                IconButton(onClick = { onForgetSavedAccount(account.username) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除已保存教务账号")
                }
            }
        }
    }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.trim() },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("学号") },
        singleLine = true,
        enabled = !usingSavedPassword,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        colors = ccitTextFieldColors(),
    )
    if (usingSavedPassword) {
        CcitCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CcitColors.Success)
                    Spacer(Modifier.width(8.dp))
                    Text("已使用本机加密保存的教务密码")
                }
                Text(
                    if (state.captchaAutofillEnabled) {
                        "验证码将自动填写。"
                    } else {
                        "请填写本次验证码。"
                    },
                    color = CcitColors.InkMuted,
                )
                CcitOutlinedButton(onClick = onUseManualCredentials, modifier = Modifier.fillMaxWidth()) {
                    Text("重新输入教务账号密码")
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("教务系统密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            colors = ccitTextFieldColors(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { rememberPassword = !rememberPassword },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = { rememberPassword = it },
            )
            Text(
                "在本机加密保存此教务账号和密码",
                modifier = Modifier.weight(1f),
            )
        }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = captchaCode,
            onValueChange = {
                captchaEdited = true
                captchaCode = it.trim()
            },
            modifier = Modifier.weight(1f).widthIn(min = 0.dp),
            label = { Text("验证码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { onLogin(username, password, captchaCode, rememberPassword) },
            ),
            colors = ccitTextFieldColors(),
        )
        Spacer(Modifier.width(10.dp))
        AcademicCaptcha(
            state.captcha,
            state.loadingCaptcha || state.recognizingCaptcha,
        )
        IconButton(
            onClick = onRefreshCaptcha,
            enabled = !state.loadingCaptcha && !state.recognizingCaptcha,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新教务验证码")
        }
    }
    CcitPrimaryButton(
        onClick = { onLogin(username, password, captchaCode, rememberPassword) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.submitting &&
            username.isNotBlank() &&
            (usingSavedPassword || password.isNotBlank()) &&
            state.captcha != null &&
            captchaCode.isNotBlank(),
    ) {
        Crossfade(targetState = state.submitting, animationSpec = tween(160), label = "academic submit") { submitting ->
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = CcitColors.Surface,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("登录学生端")
                }
            }
        }
    }
    }
}

@Composable
private fun AcademicGradeFilters(
    state: AcademicUiState,
    onSelectTerm: (String) -> Unit,
    onBestOnlyChanged: (Boolean) -> Unit,
    onQueryGrades: () -> Unit,
) {
    val termLabel = state.terms.firstOrNull { it.value == state.selectedTerm }?.label
        ?: "全部学期"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    Text("已登录学生端", color = CcitColors.Success, fontWeight = FontWeight.Bold)
    CcitSelectField(
        label = "学年学期",
        value = state.selectedTerm,
        options = state.terms.map { it.value to it.label },
        onValueChange = onSelectTerm,
        modifier = Modifier.fillMaxWidth(),
        fallbackText = termLabel,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBestOnlyChanged(!state.bestOnly) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.bestOnly, onCheckedChange = onBestOnlyChanged)
        Text("同一课程只显示最好成绩")
    }
    CcitPrimaryButton(
        onClick = onQueryGrades,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.loadingGrades,
    ) {
        Icon(Icons.Default.Search, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("查询成绩")
    }
    }
}

@Composable
private fun AcademicCaptcha(bytes: ByteArray?, loading: Boolean) {
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    CcitCard(modifier = Modifier.size(width = 112.dp, height = 50.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Crossfade(
                targetState = when {
                    loading -> 0
                    bitmap == null -> 1
                    else -> 2
                },
                animationSpec = tween(180),
                label = "academic captcha",
            ) { captchaState ->
                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                    when (captchaState) {
                        0 -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        1 -> Text("点击刷新", color = CcitColors.InkMuted)
                        else -> bitmap?.let {
                            Image(bitmap = it, contentDescription = "教务系统图形验证码")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeCard(grade: CourseGrade) {
    CcitCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(grade.courseName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(grade.semester, grade.courseCode)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        color = CcitColors.InkMuted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    grade.score.ifBlank { "—" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = CcitColors.Brown,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                listOf(
                    "学分 ${grade.credit.ifBlank { "—" }}",
                    "绩点 ${grade.gradePoint.ifBlank { "—" }}",
                    grade.assessmentMethod,
                    grade.courseAttribute,
                ).filter(String::isNotBlank).joinToString("  ·  "),
                color = CcitColors.InkMuted,
            )
            listOf(grade.scoreMark, grade.description, grade.note)
                .filter(String::isNotBlank)
                .joinToString("；")
                .takeIf(String::isNotBlank)
                ?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AcademicLoading(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(message, color = CcitColors.InkMuted)
    }
}

@Composable
private fun AcademicGradeSkeleton(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CcitSkeletonBlock(Modifier.fillMaxWidth(0.58f).height(22.dp), CircleShape)
        CcitSkeletonBlock(Modifier.fillMaxWidth().height(15.dp), CircleShape)
        CcitSkeletonBlock(Modifier.fillMaxWidth(0.78f).height(15.dp), CircleShape)
        Text(message, color = CcitColors.InkMuted)
    }
}
