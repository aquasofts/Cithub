package edu.ccit.webvpn.feature.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.SizeResolver
import edu.ccit.webvpn.core.ui.CcitCard
import edu.ccit.webvpn.core.ui.CcitColors
import edu.ccit.webvpn.core.ui.LocalReduceMotion
import edu.ccit.webvpn.core.ui.ccitBackwardNavigationTransition
import edu.ccit.webvpn.core.ui.ccitForwardNavigationTransition
import edu.ccit.webvpn.core.ui.ccitPlaceholder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.Serializable

@Serializable
private data object FeedRoute : NavKey

@Serializable
private data class ArticleRoute(val articleId: String) : NavKey

@Serializable
private data class ArticleImageRoute(val url: String) : NavKey

private val HomeWindowInsets = WindowInsets(0, 0, 0, 0)

@Composable
fun HomeRootScreen(
    active: Boolean,
    reduceMotion: Boolean,
    wechatRssUrls: List<String> = DefaultHomeFeedUrls.wechat,
    newsRssUrls: List<String> = DefaultHomeFeedUrls.news,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(FeedRoute)
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(wechatRssUrls, newsRssUrls) {
        viewModel.configure(wechatRssUrls, newsRssUrls)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        modifier = modifier.fillMaxSize(),
        transitionSpec = { ccitForwardNavigationTransition(reduceMotion) },
        popTransitionSpec = { ccitBackwardNavigationTransition(reduceMotion) },
        predictivePopTransitionSpec = { ccitBackwardNavigationTransition(reduceMotion) },
        entryProvider = entryProvider {
        entry<FeedRoute> {
            HomeFeedScreen(
                active = active,
                reduceMotion = reduceMotion,
                state = state,
                onEnsureLoaded = viewModel::ensureLoaded,
                onRefresh = viewModel::refresh,
                onArticle = {
                    viewModel.loadArticleDetail(it.id)
                    val route = ArticleRoute(it.id)
                    if (backStack.lastOrNull() != route) backStack.add(route)
                },
            )
        }
        entry<ArticleRoute> { route ->
            val article = state.article(route.articleId)
            if (article == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.isInitiallyLoading()) {
                        CircularProgressIndicator()
                    } else {
                        OutlinedButton(onClick = { backStack.removeLastOrNull() }) { Text("返回") }
                    }
                }
            } else {
                ArticleReaderScreen(
                    article = article,
                    onBack = { backStack.removeLastOrNull() },
                    onImage = { url ->
                        val imageRoute = ArticleImageRoute(url)
                        if (backStack.lastOrNull() != imageRoute) backStack.add(imageRoute)
                    },
                )
            }
        }
        entry<ArticleImageRoute> { route ->
            FullArticleImageScreen(url = route.url, onBack = { backStack.removeLastOrNull() })
        }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeFeedScreen(
    active: Boolean,
    reduceMotion: Boolean,
    state: HomeUiState,
    onEnsureLoaded: (HomeSection) -> Unit,
    onRefresh: (HomeSection) -> Unit,
    onArticle: (HomeArticle) -> Unit,
) {
    val sections = HomeSection.entries
    val pagerState = rememberPagerState(pageCount = { sections.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(active, pagerState.currentPage) {
        if (active) onEnsureLoaded(sections[pagerState.currentPage])
    }

    Scaffold(
        contentWindowInsets = HomeWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text("新闻") },
                windowInsets = HomeWindowInsets,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEachIndexed { index, section ->
                    FilterChip(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    index,
                                    animationSpec = tween(
                                        durationMillis = if (reduceMotion) 120 else 240,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }
                        },
                        label = { Text(section.label) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                beyondViewportPageCount = 1,
            ) { page ->
                val section = sections[page]
                FeedPage(
                    section = section,
                    state = state.page(section),
                    onRefresh = { onRefresh(section) },
                    onArticle = onArticle,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPage(
    section: HomeSection,
    state: FeedPageState,
    onRefresh: () -> Unit,
    onArticle: (HomeArticle) -> Unit,
) {
    val listState = rememberLazyListState()

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        AnimatedContent(
            targetState = state.initialLoading && state.articles.isEmpty(),
            modifier = Modifier.fillMaxSize(),
            label = "home feed loading",
        ) { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.errorMessage?.let { message ->
                        item(key = "error", contentType = "status") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(message, color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = onRefresh) { Text("重试") }
                            }
                        }
                    }
                    items(
                        items = state.articles,
                        key = HomeArticle::id,
                        contentType = { "article" },
                    ) { article ->
                        ArticleCard(article = article, onClick = { onArticle(article) })
                    }
                    if (state.articles.isEmpty() && state.errorMessage == null) {
                        item(key = "empty", contentType = "status") {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无内容", color = CcitColors.InkMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(article: HomeArticle, onClick: () -> Unit) {
    CcitCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (article.sourceAvatarUrl.isNotBlank()) {
                    SmoothNetworkImage(
                        url = article.sourceAvatarUrl,
                        contentDescription = "${article.sourceName}头像",
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(7.dp))
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    article.sourceName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                article.publishedAt?.let {
                    Text(
                        DateTimeFormatter.ofPattern("M-d").withZone(ZoneId.systemDefault()).format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = CcitColors.InkMuted,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (article.summary.isNotBlank()) {
                    Text(
                        article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CcitColors.InkMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
                if (article.section != HomeSection.WECHAT && article.coverUrl.isNotBlank()) {
                    SmoothNetworkImage(
                        url = article.coverUrl,
                        contentDescription = "${article.title}配图",
                        modifier = Modifier.width(104.dp).aspectRatio(1.25f),
                        shape = MaterialTheme.shapes.medium,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleReaderScreen(
    article: HomeArticle,
    onBack: () -> Unit,
    onImage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val contentKind = remember(article.id, article.html) { articleContentKind(article) }
    val canOpenOriginal = isAllowedArticleUrl(article.link, article.allowedArticleHosts)
    var originalMode by remember(article.id) { mutableStateOf(false) }
    var reloadKey by remember(article.id) { mutableStateOf(0) }
    var webView by remember(article.id, originalMode, reloadKey) { mutableStateOf<WebView?>(null) }
    var canGoBackInWebView by remember(article.id, originalMode, reloadKey) { mutableStateOf(false) }
    var loadState by remember(article.id, originalMode, reloadKey) {
        mutableStateOf<ArticleLoadState>(if (originalMode) ArticleLoadState.Loading else ArticleLoadState.Ready)
    }
    var imageMenu by remember(article.id, originalMode) { mutableStateOf<ArticleImageMenu?>(null) }
    val imageSaveAction = rememberHomeImageSaveAction(imageMenu?.url)
    val loading = loadState == ArticleLoadState.Loading
    val loadFailure = (loadState as? ArticleLoadState.Failed)?.message
    val textColor = MaterialTheme.colorScheme.onBackground.toCssHex()
    val backgroundColor = MaterialTheme.colorScheme.background.toCssHex()
    val linkColor = MaterialTheme.colorScheme.primary.toCssHex()

    LaunchedEffect(article.id, originalMode, reloadKey, loading) {
        if (originalMode && loading) {
            delay(ORIGINAL_LOAD_TIMEOUT_MILLIS)
            webView?.stopLoading()
            loadState = reduceArticleLoadState(loadState, ArticleLoadEvent.Timeout)
        }
    }

    BackHandler(enabled = canGoBackInWebView) { webView?.goBack() }
    DisposableEffect(article.id) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
    }

    Scaffold(
        contentWindowInsets = HomeWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = HomeWindowInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(article.section.label)
                    }
                },
                actions = {
                    if (!originalMode && canOpenOriginal) {
                        IconButton(onClick = { originalMode = true }) {
                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "查看原文")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!originalMode && contentKind != ArticleContentKind.COMPLETE) {
                ArticleContentPlaceholder(
                    kind = contentKind,
                    canOpenOriginal = canOpenOriginal,
                    onOpenOriginal = { originalMode = true },
                )
            } else {
                key(article.id, article.html, originalMode, reloadKey, textColor, backgroundColor, linkColor) {
                    SecureArticleWebView(
                        article = article,
                        originalMode = originalMode,
                        textColor = textColor,
                        backgroundColor = backgroundColor,
                        linkColor = linkColor,
                        onWebView = {
                            webView = it
                            canGoBackInWebView = false
                        },
                        onCanGoBackChange = { canGoBackInWebView = it },
                        onLoadEvent = { event -> loadState = reduceArticleLoadState(loadState, event) },
                        onRequestOriginal = { originalMode = true },
                        onImageClick = onImage,
                        onImageLongPress = { url, offset -> imageMenu = ArticleImageMenu(url, offset) },
                    )
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
            loadFailure?.let { message ->
                ArticleLoadFailure(
                    message = message,
                    canOpenExternal = canOpenOriginal,
                    onRetry = {
                        reloadKey += 1
                    },
                    onOpenExternal = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.link)))
                        } catch (_: ActivityNotFoundException) {
                            loadState = ArticleLoadState.Failed("没有可用的浏览器")
                        }
                    },
                )
            }
            imageMenu?.let { menu ->
                Box(Modifier.offset { menu.offset }) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { imageMenu = null },
                        containerColor = MaterialTheme.colorScheme.background,
                        shape = MaterialTheme.shapes.small,
                        shadowElevation = 7.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text("保存图片") },
                            enabled = !imageSaveAction.saving,
                            onClick = {
                                imageMenu = null
                                imageSaveAction.save()
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class ArticleImageMenu(val url: String, val offset: IntOffset)

private data class HomeImageSaveAction(
    val saving: Boolean,
    val save: () -> Unit,
)

@Composable
private fun rememberHomeImageSaveAction(url: String?): HomeImageSaveAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUrl by rememberUpdatedState(url)
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val startSaving: () -> Unit = {
        val imageUrl = pendingUrl ?: currentUrl
        if (imageUrl != null && !saving) {
            scope.launch {
                saving = true
                runCatching { saveHomeImage(context, imageUrl) }
                    .onSuccess { path -> Toast.makeText(context, "已保存到 $path", Toast.LENGTH_LONG).show() }
                    .onFailure { error ->
                        Toast.makeText(context, error.message ?: "图片保存失败", Toast.LENGTH_LONG).show()
                    }
                saving = false
                pendingUrl = null
            }
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            startSaving()
        } else {
            Toast.makeText(context, "未获得存储权限，无法保存图片", Toast.LENGTH_SHORT).show()
        }
    }
    return HomeImageSaveAction(
        saving = saving,
        save = {
            currentUrl?.let { imageUrl ->
                pendingUrl = imageUrl
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startSaving()
                } else {
                    legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeImageSaveContextMenu(
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.filterIsInstance<PressInteraction.Press>().collect { press ->
            pressOffset = press.pressPosition.round()
        }
    }
    Box(
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {},
            onLongClickLabel = "图片操作",
            onLongClick = { if (saveEnabled) expanded = true },
        ),
    ) {
        content()
        Box(Modifier.offset { pressOffset }) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = MaterialTheme.colorScheme.background,
                shape = MaterialTheme.shapes.small,
                shadowElevation = 7.dp,
            ) {
                DropdownMenuItem(
                    text = { Text("保存图片") },
                    onClick = {
                        expanded = false
                        onSave()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullArticleImageScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val cache = remember(context) { HomeImageCache.get(context) }
    var localImage by remember(url) { mutableStateOf(cache.cached(url)?.file) }
    var retry by remember(url) { mutableStateOf(0) }
    var loading by remember(url, retry) { mutableStateOf(true) }
    var failed by remember(url, retry) { mutableStateOf(false) }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    val saveAction = rememberHomeImageSaveAction(url)

    LaunchedEffect(url, retry) {
        localImage = cache.cached(url)?.file ?: cache.getOrFetch(url)?.file
    }
    val request = remember(context, url, localImage, retry) {
        ImageRequest.Builder(context)
            .data(localImage ?: url)
            .size(SizeResolver.ORIGINAL)
            .build()
    }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += pan.x
            offsetY += pan.y
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        HomeImageSaveContextMenu(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }.transformable(transform),
            onSave = saveAction.save,
            saveEnabled = !failed && !saveAction.saving,
        ) {
            AsyncImage(
                model = request,
                contentDescription = "新闻图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onLoading = {
                    loading = true
                    failed = false
                },
                onSuccess = {
                    loading = false
                    failed = false
                },
                onError = {
                    loading = false
                    failed = true
                },
            )
        }
        if (loading) CircularProgressIndicator(color = Color.White)
        if (failed) {
            Surface(
                color = Color(0xCC202124),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("图片加载失败")
                    OutlinedButton(onClick = { retry += 1 }) { Text("重试") }
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x66000000)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text("图片预览", Modifier.weight(1f), color = Color.White)
            if (saveAction.saving) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ArticleContentPlaceholder(
    kind: ArticleContentKind,
    canOpenOriginal: Boolean,
    onOpenOriginal: () -> Unit,
) {
    val message = when (kind) {
        ArticleContentKind.PLACEHOLDER -> "RSS 源暂未提供完整正文"
        ArticleContentKind.INTERACTIVE -> "文章包含暂不支持的互动内容"
        ArticleContentKind.EMPTY -> "RSS 源未提供正文"
        ArticleContentKind.COMPLETE -> return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = CcitColors.InkMuted,
        )
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "你可以查看原文，或稍后刷新订阅。",
            color = CcitColors.InkMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (canOpenOriginal) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onOpenOriginal) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看原文")
            }
        }
    }
}

@Composable
private fun ArticleLoadFailure(
    message: String,
    canOpenExternal: Boolean,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
        if (canOpenExternal) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenExternal) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("在浏览器中打开")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SecureArticleWebView(
    article: HomeArticle,
    originalMode: Boolean,
    textColor: String,
    backgroundColor: String,
    linkColor: String,
    onWebView: (WebView) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onLoadEvent: (ArticleLoadEvent) -> Unit,
    onRequestOriginal: () -> Unit,
    onImageClick: (String) -> Unit,
    onImageLongPress: (String, IntOffset) -> Unit,
) {
    val context = LocalContext.current
    val imageCache = remember(context) { HomeImageCache.get(context) }
    val cachedArticleImages = remember(article.id, article.html) { articleImageUrls(article).toSet() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                var lastTouchOffset = IntOffset.Zero
                var blockingMultiTouch = false
                onWebView(this)
                setBackgroundColor(AndroidColor.parseColor(backgroundColor))
                // Local article HTML is sanitized and only our image loader script remains.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = originalMode
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.safeBrowsingEnabled = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setSupportZoom(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        lastTouchOffset = IntOffset(event.x.toInt(), event.y.toInt())
                    }
                    if (event.pointerCount > 1) blockingMultiTouch = true
                    val consume = blockingMultiTouch
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        blockingMultiTouch = false
                    }
                    consume
                }
                setOnLongClickListener {
                    val hit = hitTestResult
                    val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    if (isImage) {
                        val pressOffset = lastTouchOffset
                        imageUrlAt(pressOffset) { url ->
                            if (isSafeHttpsUrl(url)) onImageLongPress(url, pressOffset)
                        }
                    }
                    isImage
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        if (originalMode || request?.isForMainFrame != false) return null
                        val url = request.url.toString()
                        if (url !in cachedArticleImages) return null
                        return imageCache.getOrFetchBlocking(url)?.let { cached ->
                            WebResourceResponse(cached.mimeType, null, cached.file.inputStream()).apply {
                                responseHeaders = mapOf(
                                    "Cache-Control" to "public, max-age=31536000, immutable",
                                    "Access-Control-Allow-Origin" to "*",
                                )
                            }
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (originalMode) onLoadEvent(ArticleLoadEvent.Started)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.installArticleImageClickHandler()
                        onCanGoBackChange(view?.canGoBack() == true)
                        onLoadEvent(ArticleLoadEvent.Finished)
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        onCanGoBackChange(view.canGoBack())
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onLoadEvent(ArticleLoadEvent.Failed("原文加载失败"))
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onLoadEvent(
                                ArticleLoadEvent.Failed(
                                    "原文加载失败（HTTP ${errorResponse?.statusCode ?: "未知"}）",
                                ),
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        articleImageUrlFromNavigation(url)?.let { imageUrl ->
                            onImageClick(imageUrl)
                            return true
                        }
                        if (!isSafeHttpsUrl(url)) return true
                        if (isAllowedArticleUrl(url, article.allowedArticleHosts)) {
                            if (originalMode) return false
                            onRequestOriginal()
                            return true
                        }
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                            // The link remains blocked when no safe external handler is available.
                        }
                        return true
                    }
                }
                if (originalMode) {
                    loadUrl(article.link)
                } else {
                    loadDataWithBaseURL(
                        article.link,
                        articleHtmlDocument(
                            body = article.html,
                            textColor = textColor,
                            backgroundColor = backgroundColor,
                            linkColor = linkColor,
                            title = article.title,
                            sourceName = article.sourceName,
                            publishedAt = article.publishedAt?.let {
                                DateTimeFormatter.ofPattern("yyyy年M月d日")
                                    .withZone(ZoneId.systemDefault()).format(it)
                            }.orEmpty(),
                        ),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            }
        },
    )
}

private const val ArticleImageScheme = "ccit-article-image"

private fun WebView.installArticleImageClickHandler() {
    evaluateJavascript(
        """
        (() => {
          if (window.__ccitArticleImageHandlerInstalled) return;
          window.__ccitArticleImageHandlerInstalled = true;
          document.addEventListener('click', (event) => {
            const target = event.target;
            const image = target && target.closest ? target.closest('img') : null;
            if (!image) return;
            const url = image.currentSrc || image.src || image.dataset.src || '';
            if (!url.startsWith('https://')) return;
            event.preventDefault();
            event.stopPropagation();
            window.location.href = '$ArticleImageScheme://open?url=' + encodeURIComponent(url);
          }, true);
        })();
        """.trimIndent(),
        null,
    )
}

private fun WebView.imageUrlAt(offset: IntOffset, onResult: (String) -> Unit) {
    val script = """
        (() => {
          const density = window.devicePixelRatio || 1;
          const target = document.elementFromPoint(${offset.x} / density, ${offset.y} / density);
          const image = target && target.closest ? target.closest('img') : null;
          return image ? (image.currentSrc || image.src || image.dataset.src || '') : '';
        })();
    """.trimIndent()
    evaluateJavascript(script) { encoded ->
        val url = runCatching { org.json.JSONTokener(encoded).nextValue() as? String }.getOrNull().orEmpty()
        if (url.isNotBlank()) post { onResult(url) }
    }
}

private fun articleImageUrlFromNavigation(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (uri.scheme != ArticleImageScheme || uri.host != "open") return null
    return uri.getQueryParameter("url")?.takeIf(::isSafeHttpsUrl)
}

internal sealed interface ArticleLoadState {
    data object Loading : ArticleLoadState
    data object Ready : ArticleLoadState
    data class Failed(val message: String) : ArticleLoadState
}

internal sealed interface ArticleLoadEvent {
    data object Started : ArticleLoadEvent
    data object Finished : ArticleLoadEvent
    data object Timeout : ArticleLoadEvent
    data class Failed(val message: String) : ArticleLoadEvent
}

internal fun reduceArticleLoadState(
    current: ArticleLoadState,
    event: ArticleLoadEvent,
): ArticleLoadState = when (event) {
    ArticleLoadEvent.Started -> ArticleLoadState.Loading
    ArticleLoadEvent.Finished -> if (current is ArticleLoadState.Failed) current else ArticleLoadState.Ready
    ArticleLoadEvent.Timeout -> if (current == ArticleLoadState.Loading) {
        ArticleLoadState.Failed("原文加载超时")
    } else {
        current
    }
    is ArticleLoadEvent.Failed -> ArticleLoadState.Failed(event.message)
}

internal fun articleHtmlDocument(
    body: String,
    textColor: String = "#1D1B20",
    backgroundColor: String = "#FFFBFE",
    linkColor: String = "#415F91",
    title: String = "",
    sourceName: String = "",
    publishedAt: String = "",
): String {
    val document = org.jsoup.Jsoup.parseBodyFragment(body)
    document.select("script, noscript, object, embed, form").remove()
    document.getAllElements().forEach { element ->
        element.attributes().asList()
            .filter { it.key.startsWith("on", ignoreCase = true) }
            .forEach { element.removeAttr(it.key) }
    }
    document.select("img").forEach { image ->
        val source = image.attr("src").ifBlank { image.attr("data-src") }
        if (source.isBlank()) {
            image.remove()
        } else {
            image.attr("data-src", source)
            image.removeAttr("src")
            image.addClass("article-image")
            image.addClass("is-loading")
            image.attr("loading", "lazy")
            image.attr("decoding", "async")
            image.attr("fetchpriority", "low")
            val width = image.attr("width").filter(Char::isDigit).toIntOrNull()
            val height = image.attr("height").filter(Char::isDigit).toIntOrNull()
            if (width != null && height != null && width > 0 && height > 0) {
                image.attr("style", image.attr("style") + ";--article-image-ratio:$width/$height")
            }
        }
    }
    val safeBody = document.body().html()
    val safeTitle = org.jsoup.nodes.Entities.escape(title)
    val metadata = listOf(sourceName, publishedAt).filter(String::isNotBlank)
        .joinToString(" · ") { org.jsoup.nodes.Entities.escape(it) }
    val header = if (safeTitle.isBlank()) "" else """
        <header class="article-header">
          <h1>$safeTitle</h1>
          ${if (metadata.isBlank()) "" else "<div class=\"article-meta\">$metadata</div>"}
        </header>
    """.trimIndent()
    return """
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; media-src https:; frame-src https:; style-src 'unsafe-inline'; script-src 'nonce-ccit-images'; font-src https: data:">
        <style>
          html, body { margin: 0; padding: 0; max-width: 100%; overflow-wrap: anywhere; background: $backgroundColor; color: $textColor; }
          body { padding: 16px; box-sizing: border-box; font-family: sans-serif; font-size: 16px; line-height: 1.75; }
          a { color: $linkColor; }
          .article-header { margin: 2px 0 24px; }
          .article-header h1 { margin: 0 0 10px; font-size: 28px; line-height: 1.3; }
          .article-meta { color: $textColor; opacity: .62; font-size: 13px; }
          img, video, iframe, table { max-width: 100% !important; height: auto !important; }
          .article-image { display: block; width: 100% !important; max-width: 100% !important; height: auto !important; min-height: 96px; aspect-ratio: var(--article-image-ratio, 4 / 3); margin: 12px auto; object-fit: contain; border-radius: 8px; background: linear-gradient(100deg, rgba(127,127,127,.10) 20%, rgba(127,127,127,.24) 40%, rgba(127,127,127,.10) 60%); background-size: 240% 100%; animation: image-shimmer 1.2s linear infinite; opacity: .72; transform: scale(.992); filter: blur(6px); transition: opacity 240ms ease, transform 240ms ease, filter 240ms ease; }
          .article-image.is-loaded { min-height: 0; opacity: 1; transform: scale(1); filter: none; animation: none; background: transparent; }
          .article-image.is-error { min-height: 96px; opacity: .42; transform: none; filter: none; animation: none; }
          @keyframes image-shimmer { from { background-position: 120% 0; } to { background-position: -120% 0; } }
          iframe { width: 100% !important; min-height: 220px; border: 0; }
          pre { overflow-x: auto; white-space: pre-wrap; }
        </style>
      </head>
      <body>$header$safeBody
        <script nonce="ccit-images">
          (() => {
            const load = (img) => {
              if (img.dataset.started) return;
              img.dataset.started = '1';
              img.addEventListener('load', () => {
                if (img.naturalWidth > 0 && img.naturalHeight > 0) {
                  img.style.setProperty('--article-image-ratio', `${'$'}{img.naturalWidth}/${'$'}{img.naturalHeight}`);
                }
                requestAnimationFrame(() => img.classList.add('is-loaded'));
              }, { once: true });
              img.addEventListener('error', () => img.classList.add('is-error'), { once: true });
              img.src = img.dataset.src;
              if (img.complete && img.naturalWidth > 0) {
                img.style.setProperty('--article-image-ratio', `${'$'}{img.naturalWidth}/${'$'}{img.naturalHeight}`);
                requestAnimationFrame(() => img.classList.add('is-loaded'));
              }
            };
            const images = document.querySelectorAll('img[data-src]');
            if (!('IntersectionObserver' in window)) { images.forEach(load); return; }
            const observer = new IntersectionObserver((entries) => entries.forEach((entry) => {
              if (entry.isIntersecting) { load(entry.target); observer.unobserve(entry.target); }
            }), { rootMargin: '600px 0px' });
            images.forEach((img) => observer.observe(img));
          })();
        </script>
      </body>
    </html>
    """.trimIndent()
}

private fun Color.toCssHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

internal val HomeSection.label: String
    get() = when (this) {
        HomeSection.WECHAT -> "公众号"
        HomeSection.NEWS -> "校内新闻"
        HomeSection.OFFICIAL -> "官方新闻"
    }

@Composable
private fun SmoothNetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape,
    contentScale: ContentScale,
) {
    val context = LocalContext.current
    val cache = remember(context) { HomeImageCache.get(context) }
    val reduceMotion = LocalReduceMotion.current
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    var localImage by remember(url) { mutableStateOf(cache.cached(url)?.file) }
    LaunchedEffect(url) {
        if (localImage == null) localImage = cache.getOrFetch(url)?.file
    }
    val request = remember(context, localImage, reduceMotion) {
        ImageRequest.Builder(context)
            .data(localImage)
            .crossfade(if (reduceMotion) 0 else IMAGE_CROSSFADE_MILLIS)
            .build()
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .ccitPlaceholder(visible = loading, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (localImage != null) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onLoading = {
                    loading = true
                    failed = false
                },
                onSuccess = {
                    loading = false
                    failed = false
                },
                onError = {
                    loading = false
                    failed = true
                },
            )
        }
        if (failed) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = "图片加载失败",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val ORIGINAL_LOAD_TIMEOUT_MILLIS = 30_000L
private const val IMAGE_CROSSFADE_MILLIS = 180
private const val OFFICIAL_COVER_PREFETCH_COUNT = 20

internal fun officialCoverUrlsToPrefetch(
    articles: List<HomeArticle>,
    lastVisibleIndex: Int,
    count: Int = OFFICIAL_COVER_PREFETCH_COUNT,
): List<String> = articles
    .drop((lastVisibleIndex + 1).coerceAtLeast(0))
    .asSequence()
    .map(HomeArticle::coverUrl)
    .filter(String::isNotBlank)
    .distinct()
    .take(count.coerceAtLeast(0))
    .toList()
