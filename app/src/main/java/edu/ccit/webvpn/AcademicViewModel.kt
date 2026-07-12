package edu.ccit.webvpn

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.ccit.webvpn.core.captcha.CaptchaAutomation
import edu.ccit.webvpn.core.academic.AcademicApiException
import edu.ccit.webvpn.core.academic.AcademicOverview
import edu.ccit.webvpn.core.academic.AcademicRepository
import edu.ccit.webvpn.core.academic.AcademicTerm
import edu.ccit.webvpn.core.academic.AcademicTimetable
import edu.ccit.webvpn.core.academic.CourseGrade
import edu.ccit.webvpn.core.academic.EvaluationAnswer
import edu.ccit.webvpn.core.academic.EvaluationBatch
import edu.ccit.webvpn.core.academic.EvaluationCourse
import edu.ccit.webvpn.core.academic.EvaluationForm
import edu.ccit.webvpn.core.academic.SelectedCourse
import edu.ccit.webvpn.core.webvpn.AcademicCredentialStore
import edu.ccit.webvpn.core.webvpn.SavedAcademicAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AcademicUiState(
    val captchaAutofillEnabled: Boolean = false,
    val active: Boolean = false,
    val initializing: Boolean = false,
    val loggedIn: Boolean = false,
    val defaultUsername: String = "",
    val savedAccounts: List<SavedAcademicAccount> = emptyList(),
    val selectedSavedUsername: String? = null,
    val captcha: ByteArray? = null,
    val recognizedCaptchaCode: String = "",
    val loadingCaptcha: Boolean = false,
    val recognizingCaptcha: Boolean = false,
    val submitting: Boolean = false,
    val terms: List<AcademicTerm> = emptyList(),
    val selectedTerm: String = "",
    val bestOnly: Boolean = false,
    val grades: List<CourseGrade> = emptyList(),
    val loadingGrades: Boolean = false,
    val timetable: AcademicTimetable? = null,
    val loadingTimetable: Boolean = false,
    val courseSelectionTerms: List<AcademicTerm> = emptyList(),
    val selectedCourseSelectionTerm: String = "",
    val selectedCourses: List<SelectedCourse> = emptyList(),
    val loadingCourseSelection: Boolean = false,
    val evaluationBatches: List<EvaluationBatch> = emptyList(),
    val evaluationCourses: List<EvaluationCourse> = emptyList(),
    val selectedEvaluationBatchPath: String? = null,
    val evaluationForm: EvaluationForm? = null,
    val loadingEvaluation: Boolean = false,
    val savingEvaluation: Boolean = false,
    val webViewCookies: List<String> = emptyList(),
    val message: String? = null,
)

class AcademicViewModel(
    private val repository: AcademicRepository,
    private val credentialStore: AcademicCredentialStore,
    private val captchaAutomation: CaptchaAutomation,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AcademicUiState(captchaAutofillEnabled = captchaAutomation.isEnabled),
    )
    val uiState: StateFlow<AcademicUiState> = _uiState.asStateFlow()
    private var activeUsername: String? = null
    private var sessionGeneration = 0
    private var initializationJob: Job? = null

    fun onWebVpnReady(username: String) {
        val normalized = username.trim()
        if (_uiState.value.active && activeUsername == normalized) return
        activeUsername = normalized
        val generation = ++sessionGeneration
        initializationJob?.cancel()
        initializationJob = viewModelScope.launch {
            if (!captchaAutomation.isEnabled) {
                runCatching { credentialStore.clearLastAcademicLoginCredential() }
            }
            _uiState.value = AcademicUiState(
                captchaAutofillEnabled = captchaAutomation.isEnabled,
                active = true,
                initializing = true,
                defaultUsername = normalized,
            )
            val savedAccounts = runCatching { credentialStore.getSavedAcademicAccounts() }
                .getOrDefault(emptyList())
            if (generation != sessionGeneration) return@launch
            _uiState.update {
                it.copy(
                    savedAccounts = savedAccounts,
                    selectedSavedUsername = normalized.takeIf { username ->
                        savedAccounts.any { account -> account.username == username }
                    },
                )
            }

            runCatching { repository.restoreSession() }
                .onSuccess { overview ->
                    if (generation != sessionGeneration) return@onSuccess
                    if (overview == null) {
                        if (recoverAcademicSession()) loadGrades()
                    } else {
                        enterGradeState(overview)
                        loadGrades()
                    }
                }
                .onFailure { error ->
                    if (generation != sessionGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            initializing = false,
                            message = error.message ?: "连接教务系统失败",
                        )
                    }
                    loadCaptcha()
                }
        }
    }

    fun onWebVpnCleared() {
        activeUsername = null
        sessionGeneration += 1
        initializationJob?.cancel()
        initializationJob = null
        _uiState.value = AcademicUiState(
            captchaAutofillEnabled = captchaAutomation.isEnabled,
        )
    }

    fun refreshCaptcha() {
        if (!_uiState.value.active ||
            _uiState.value.loadingCaptcha ||
            _uiState.value.recognizingCaptcha
        ) return
        viewModelScope.launch { loadCaptcha() }
    }

    fun selectSavedAccount(username: String) {
        if (_uiState.value.savedAccounts.none { it.username == username }) return
        _uiState.update {
            it.copy(defaultUsername = username, selectedSavedUsername = username)
        }
    }

    fun useManualCredentials() {
        _uiState.update { it.copy(selectedSavedUsername = null) }
    }

    fun forgetSavedAccount(username: String) {
        viewModelScope.launch {
            runCatching { credentialStore.deleteAcademicCredential(username) }
                .onSuccess {
                    val accounts = credentialStore.getSavedAcademicAccounts()
                    _uiState.update { state ->
                        state.copy(
                            savedAccounts = accounts,
                            selectedSavedUsername = state.selectedSavedUsername
                                ?.takeIf { selected -> accounts.any { it.username == selected } },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(message = error.message ?: "删除已保存教务账号失败") }
                }
        }
    }

    fun login(
        username: String,
        password: String,
        captchaCode: String,
        rememberPassword: Boolean,
    ) {
        val state = _uiState.value
        if (!state.active || state.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, message = null) }
            try {
                val normalizedUsername = username.trim()
                val selectedUsername = state.selectedSavedUsername
                val resolvedPassword = if (selectedUsername == normalizedUsername) {
                    credentialStore.getSavedAcademicPassword(normalizedUsername)
                        ?: error("本机没有可用的已保存密码，请重新输入")
                } else {
                    password
                }
                val overview = repository.login(normalizedUsername, resolvedPassword, captchaCode)
                val shouldSave = rememberPassword || selectedUsername == normalizedUsername
                var saveWarning: String? = null
                if (captchaAutomation.isEnabled) {
                    runCatching {
                        credentialStore.saveLastAcademicLoginCredential(
                            normalizedUsername,
                            resolvedPassword,
                        )
                    }.onFailure {
                        saveWarning = "已登录，但自动登录设置保存失败"
                    }
                } else {
                    runCatching { credentialStore.clearLastAcademicLoginCredential() }
                }
                if (shouldSave) {
                    runCatching {
                        credentialStore.saveAcademicCredential(normalizedUsername, resolvedPassword)
                    }.onFailure {
                        saveWarning = "已登录，但教务账号密码保存失败"
                    }
                }
                val savedAccounts = runCatching { credentialStore.getSavedAcademicAccounts() }
                    .getOrDefault(state.savedAccounts)
                enterGradeState(overview)
                _uiState.update {
                    it.copy(
                        defaultUsername = normalizedUsername,
                        savedAccounts = savedAccounts,
                        selectedSavedUsername = normalizedUsername.takeIf { shouldSave },
                        message = saveWarning,
                    )
                }
                loadGrades()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        submitting = false,
                        loggedIn = false,
                        captcha = null,
                        message = "请检查账号、密码和验证码",
                    )
                }
                loadCaptcha()
            }
        }
    }

    fun selectTerm(term: String) {
        if (_uiState.value.terms.none { it.value == term }) return
        _uiState.update { it.copy(selectedTerm = term) }
    }

    fun setBestOnly(bestOnly: Boolean) {
        _uiState.update { it.copy(bestOnly = bestOnly) }
    }

    fun queryGrades() {
        if (!_uiState.value.loggedIn || _uiState.value.loadingGrades) return
        viewModelScope.launch { loadGrades() }
    }

    fun queryTimetable(semester: String? = null) {
        val state = _uiState.value
        if (!state.loggedIn || state.loadingTimetable) return
        viewModelScope.launch { loadTimetable(semester) }
    }

    fun selectCourseSelectionTerm(term: String) {
        if (_uiState.value.courseSelectionTerms.none { it.value == term }) return
        _uiState.update { it.copy(selectedCourseSelectionTerm = term) }
    }

    fun queryCourseSelection() {
        val state = _uiState.value
        if (!state.loggedIn || state.loadingCourseSelection) return
        viewModelScope.launch { loadCourseSelection() }
    }

    fun loadEvaluationBatches() {
        val state = _uiState.value
        if (!state.loggedIn || state.loadingEvaluation) return
        viewModelScope.launch { requestEvaluationBatches() }
    }

    fun openEvaluationBatch(path: String) {
        val state = _uiState.value
        if (!state.loggedIn || state.loadingEvaluation) return
        viewModelScope.launch { requestEvaluationCourses(path) }
    }

    fun openEvaluationCourse(path: String) {
        val state = _uiState.value
        if (!state.loggedIn || state.loadingEvaluation) return
        viewModelScope.launch { requestEvaluationForm(path) }
    }

    fun closeEvaluationForm() {
        _uiState.update { it.copy(evaluationForm = null) }
    }

    fun closeEvaluationBatch() {
        _uiState.update {
            it.copy(
                selectedEvaluationBatchPath = null,
                evaluationCourses = emptyList(),
                evaluationForm = null,
            )
        }
    }

    fun saveEvaluation(
        answers: List<EvaluationAnswer>,
        suggestion: String,
        submit: Boolean,
    ) {
        val state = _uiState.value
        val form = state.evaluationForm ?: return
        val batchPath = state.selectedEvaluationBatchPath ?: return
        if (state.savingEvaluation) return
        viewModelScope.launch {
            _uiState.update { it.copy(savingEvaluation = true, message = null) }
            runCatching { repository.saveEvaluation(form, answers, suggestion, submit) }
                .onSuccess { responseMessage ->
                    _uiState.update {
                        it.copy(
                            evaluationForm = null,
                            savingEvaluation = false,
                            message = responseMessage ?: if (submit) "评价已提交" else "评价已保存",
                        )
                    }
                    requestEvaluationCourses(batchPath)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            savingEvaluation = false,
                            message = error.message ?: if (submit) "评价提交失败" else "评价保存失败",
                        )
                    }
                }
        }
    }

    fun logout() {
        val state = _uiState.value
        if (!state.loggedIn || state.submitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, message = null) }
            runCatching { repository.logout() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = error.message ?: "退出教务系统失败")
                    }
                }
            runCatching { credentialStore.clearLastAcademicLoginCredential() }
            _uiState.update {
                it.copy(
                    loggedIn = false,
                    submitting = false,
                    terms = emptyList(),
                    selectedTerm = "",
                    grades = emptyList(),
                    timetable = null,
                    loadingTimetable = false,
                    courseSelectionTerms = emptyList(),
                    selectedCourseSelectionTerm = "",
                    selectedCourses = emptyList(),
                    loadingCourseSelection = false,
                    evaluationBatches = emptyList(),
                    evaluationCourses = emptyList(),
                    selectedEvaluationBatchPath = null,
                    evaluationForm = null,
                    loadingEvaluation = false,
                    savingEvaluation = false,
                    webViewCookies = emptyList(),
                )
            }
            loadCaptcha()
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun enterGradeState(overview: AcademicOverview) {
        _uiState.update {
            it.copy(
                initializing = false,
                loggedIn = true,
                captcha = null,
                recognizedCaptchaCode = "",
                recognizingCaptcha = false,
                submitting = false,
                terms = overview.terms,
                selectedTerm = overview.defaultTerm,
                grades = emptyList(),
                webViewCookies = repository.webViewCookies(),
            )
        }
    }

    private suspend fun recoverAcademicSession(): Boolean {
        if (!captchaAutomation.isEnabled) {
            val message = "登录状态已失效，请重新登录".takeIf { _uiState.value.loggedIn }
            enterLoginState(message)
            return false
        }
        val credential = runCatching { credentialStore.getLastAcademicLoginCredential() }
            .getOrNull()
        if (credential == null) {
            enterLoginState(message = null)
            return false
        }

        repeat(MaxAutomaticLoginAttempts) { attempt ->
            try {
                val captcha = repository.loadCaptcha()
                val code = captchaAutomation.recognize(captcha)
                if (code.isBlank()) error("验证码识别失败")
                val overview = repository.login(credential.username, credential.password, code)
                enterGradeState(overview)
                _uiState.update {
                    it.copy(
                        defaultUsername = credential.username,
                        selectedSavedUsername = credential.username.takeIf { username ->
                            it.savedAccounts.any { account -> account.username == username }
                        },
                        message = null,
                    )
                }
                return true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (attempt < MaxAutomaticLoginAttempts - 1) delay(AutomaticLoginRetryDelayMs)
            }
        }

        enterLoginState("登录状态已失效，请重新登录")
        return false
    }

    private suspend fun enterLoginState(message: String?) {
        _uiState.update {
            it.copy(
                initializing = false,
                loggedIn = false,
                submitting = false,
                terms = emptyList(),
                selectedTerm = "",
                grades = emptyList(),
                loadingGrades = false,
                timetable = null,
                loadingTimetable = false,
                courseSelectionTerms = emptyList(),
                selectedCourseSelectionTerm = "",
                selectedCourses = emptyList(),
                loadingCourseSelection = false,
                evaluationBatches = emptyList(),
                evaluationCourses = emptyList(),
                selectedEvaluationBatchPath = null,
                evaluationForm = null,
                loadingEvaluation = false,
                savingEvaluation = false,
                webViewCookies = emptyList(),
                message = message,
            )
        }
        loadCaptcha()
    }

    private suspend fun loadCaptcha() {
        _uiState.update {
            it.copy(
                captcha = null,
                recognizedCaptchaCode = "",
                loadingCaptcha = true,
                recognizingCaptcha = false,
            )
        }
        runCatching { repository.loadCaptcha() }
            .onSuccess { image ->
                _uiState.update {
                    it.copy(
                        captcha = image,
                        loadingCaptcha = false,
                        recognizingCaptcha = captchaAutomation.isEnabled,
                    )
                }
                if (!captchaAutomation.isEnabled) return@onSuccess
                val recognizedCode = runCatching { captchaAutomation.recognize(image) }
                    .getOrDefault("")
                _uiState.update {
                    if (it.captcha === image) {
                        it.copy(
                            recognizedCaptchaCode = recognizedCode,
                            recognizingCaptcha = false,
                        )
                    } else {
                        it
                    }
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        captcha = null,
                        recognizedCaptchaCode = "",
                        loadingCaptcha = false,
                        recognizingCaptcha = false,
                        message = error.message ?: "教务验证码加载失败",
                    )
                }
            }
    }

    private suspend fun loadGrades(allowRecovery: Boolean = true) {
        val state = _uiState.value
        _uiState.update { it.copy(loadingGrades = true, message = null) }
        runCatching { repository.loadGrades(state.selectedTerm, state.bestOnly) }
            .onSuccess { grades ->
                _uiState.update { it.copy(grades = grades, loadingGrades = false) }
            }
            .onFailure { error ->
                val loginRequired = (error as? AcademicApiException)?.loginRequired == true
                if (loginRequired && allowRecovery) {
                    _uiState.update { it.copy(loadingGrades = false) }
                    if (recoverAcademicSession()) loadGrades(allowRecovery = false)
                } else {
                    _uiState.update {
                        it.copy(
                            loggedIn = !loginRequired,
                            loadingGrades = false,
                            grades = emptyList(),
                            message = error.message ?: "成绩加载失败",
                        )
                    }
                    if (loginRequired) loadCaptcha()
                }
            }
    }

    private suspend fun loadTimetable(semester: String?, allowRecovery: Boolean = true) {
        _uiState.update { it.copy(loadingTimetable = true, message = null) }
        runCatching { repository.loadTimetable(semester) }
            .onSuccess { timetable ->
                _uiState.update { it.copy(timetable = timetable, loadingTimetable = false) }
            }
            .onFailure { error ->
                val loginRequired = (error as? AcademicApiException)?.loginRequired == true
                if (loginRequired && allowRecovery) {
                    _uiState.update { it.copy(loadingTimetable = false) }
                    if (recoverAcademicSession()) {
                        loadTimetable(semester, allowRecovery = false)
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            loggedIn = !loginRequired,
                            loadingTimetable = false,
                            timetable = null,
                            message = error.message ?: "理论课表加载失败",
                        )
                    }
                    if (loginRequired) loadCaptcha()
                }
            }
    }

    private suspend fun loadCourseSelection(allowRecovery: Boolean = true) {
        _uiState.update { it.copy(loadingCourseSelection = true, message = null) }
        runCatching {
            var state = _uiState.value
            if (state.courseSelectionTerms.isEmpty()) {
                val overview = repository.loadCourseSelectionOverview()
                _uiState.update {
                    it.copy(
                        courseSelectionTerms = overview.terms,
                        selectedCourseSelectionTerm = overview.defaultTerm,
                    )
                }
                state = _uiState.value
            }
            repository.loadCourseSelectionResults(state.selectedCourseSelectionTerm)
        }.onSuccess { courses ->
            _uiState.update { it.copy(selectedCourses = courses, loadingCourseSelection = false) }
        }.onFailure { error ->
            val loginRequired = (error as? AcademicApiException)?.loginRequired == true
            if (loginRequired && allowRecovery) {
                _uiState.update { it.copy(loadingCourseSelection = false) }
                if (recoverAcademicSession()) loadCourseSelection(allowRecovery = false)
            } else {
                _uiState.update {
                    it.copy(
                        loggedIn = !loginRequired,
                        loadingCourseSelection = false,
                        selectedCourses = emptyList(),
                        message = error.message ?: "选课结果加载失败",
                    )
                }
                if (loginRequired) loadCaptcha()
            }
        }
    }

    private suspend fun requestEvaluationBatches(allowRecovery: Boolean = true) {
        _uiState.update { it.copy(loadingEvaluation = true, message = null) }
        runCatching { repository.loadEvaluationBatches() }
            .onSuccess { batches ->
                _uiState.update {
                    it.copy(
                        evaluationBatches = batches,
                        evaluationCourses = emptyList(),
                        selectedEvaluationBatchPath = null,
                        evaluationForm = null,
                        loadingEvaluation = false,
                    )
                }
            }
            .onFailure { error -> handleEvaluationFailure(error, allowRecovery) {
                requestEvaluationBatches(allowRecovery = false)
            } }
    }

    private suspend fun requestEvaluationCourses(path: String, allowRecovery: Boolean = true) {
        _uiState.update { it.copy(loadingEvaluation = true, evaluationForm = null, message = null) }
        runCatching { repository.loadEvaluationCourses(path) }
            .onSuccess { courses ->
                _uiState.update {
                    it.copy(
                        selectedEvaluationBatchPath = path,
                        evaluationCourses = courses,
                        evaluationForm = null,
                        loadingEvaluation = false,
                    )
                }
            }
            .onFailure { error -> handleEvaluationFailure(error, allowRecovery) {
                requestEvaluationCourses(path, allowRecovery = false)
            } }
    }

    private suspend fun requestEvaluationForm(path: String, allowRecovery: Boolean = true) {
        _uiState.update { it.copy(loadingEvaluation = true, evaluationForm = null, message = null) }
        runCatching { repository.loadEvaluationForm(path) }
            .onSuccess { form ->
                _uiState.update { it.copy(evaluationForm = form, loadingEvaluation = false) }
            }
            .onFailure { error -> handleEvaluationFailure(error, allowRecovery) {
                requestEvaluationForm(path, allowRecovery = false)
            } }
    }

    private suspend fun handleEvaluationFailure(
        error: Throwable,
        allowRecovery: Boolean,
        retry: suspend () -> Unit,
    ) {
        val loginRequired = (error as? AcademicApiException)?.loginRequired == true
        if (loginRequired && allowRecovery) {
            _uiState.update { it.copy(loadingEvaluation = false, savingEvaluation = false) }
            if (recoverAcademicSession()) retry()
        } else {
            _uiState.update {
                it.copy(
                    loggedIn = !loginRequired,
                    loadingEvaluation = false,
                    savingEvaluation = false,
                    message = error.message ?: "学生评价加载失败",
                )
            }
            if (loginRequired) loadCaptcha()
        }
    }

    override fun onCleared() {
        initializationJob?.cancel()
        captchaAutomation.close()
        super.onCleared()
    }

    class Factory(
        private val repository: AcademicRepository,
        private val credentialStore: AcademicCredentialStore,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AcademicViewModel::class.java))
            return AcademicViewModel(
                repository,
                credentialStore,
                CaptchaAutomationProvider.get(context),
            ) as T
        }
    }

    private companion object {
        const val AutomaticLoginRetryDelayMs = 1_000L
        const val MaxAutomaticLoginAttempts = 10
    }
}
