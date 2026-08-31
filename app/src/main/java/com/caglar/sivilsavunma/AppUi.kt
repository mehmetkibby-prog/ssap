package com.caglar.sivilsavunma

import androidx.activity.compose.BackHandler
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Indigo = Color(0xFF4554C7)
private val Mint = Color(0xFF35B287)
private val Rose = Color(0xFFE14E73)
private val Page = Color(0xFFF6F7FB)
private val HeroBrush = Brush.linearGradient(listOf(Color(0xFF29347C), Color(0xFF2490B5)))

private sealed interface Screen {
    data object Home : Screen
    data object Tests : Screen
    data object Favorites : Screen
    data object Wrongs : Screen
    data object Saved : Screen
    data object OfficialExam : Screen
    data object ConstitutionAI : Screen
    data class Category(val category: StudyCategory) : Screen
    data class SetDetail(val setID: String) : Screen
    data class FavoriteGroup(val setID: String) : Screen
    data class WrongGroup(val setID: String) : Screen
    data class Quiz(val session: QuizSession, val returnTo: Screen) : Screen
    data class Result(val title: String, val correct: Int, val wrong: Int, val total: Int, val returnTo: Screen) : Screen
}

@Composable
fun SivilSavunmaApp(repo: StudyRepository) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val rootTabs = listOf(
        Triple("Ana", "⌂", Screen.Home),
        Triple("Testler", "▦", Screen.Tests),
        Triple("Favoriler", "♥", Screen.Favorites),
        Triple("Yanlışlar", "!", Screen.Wrongs),
        Triple("Kayıtlı", "▣", Screen.Saved)
    )

    val showBottom = screen !is Screen.Quiz && screen !is Screen.Result

    Scaffold(
        containerColor = Page,
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    rootTabs.forEach { (label, icon, target) ->
                        val selected = when (screen) {
                            is Screen.Home -> target is Screen.Home
                            is Screen.Tests, is Screen.Category, is Screen.SetDetail -> target is Screen.Tests
                            is Screen.Favorites, is Screen.FavoriteGroup -> target is Screen.Favorites
                            is Screen.Wrongs, is Screen.WrongGroup -> target is Screen.Wrongs
                            is Screen.Saved -> target is Screen.Saved
                            else -> false
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { screen = target },
                            icon = { Text(icon, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(repo,
                    onTests = { screen = Screen.Tests },
                    onSaved = { screen = Screen.Saved },
                    onWrongs = { screen = Screen.Wrongs },
                    onCategory = { screen = Screen.Category(it) }
                )

                Screen.Tests -> TestsScreen(
                    repo,
                    onOpen = { screen = Screen.SetDetail(it.id) },
                    onOfficial = { screen = Screen.OfficialExam },
                    onAI = { screen = Screen.ConstitutionAI }
                )

                is Screen.Category -> CategoryScreen(repo, s.category,
                    onBack = { screen = Screen.Home },
                    onOpen = { screen = Screen.SetDetail(it.id) }
                )

                is Screen.SetDetail -> {
                    val set = repo.setFor(s.setID)
                    if (set != null) {
                        SetDetailScreen(
                            repo = repo,
                            set = set,
                            onBack = { screen = Screen.Tests },
                            onStart = { count ->
                                val qs = set.questions.shuffled().take(count.coerceAtMost(set.questions.size))
                                val session = repo.newSession(set, qs, scoreSetID = set.scoreKey)
                                screen = Screen.Quiz(session, Screen.SetDetail(set.id))
                            }
                        )
                    }
                }

                Screen.OfficialExam -> OfficialExamScreen(
                    repo = repo,
                    onBack = { screen = Screen.Tests },
                    onStart = { set -> screen = Screen.Quiz(repo.newSession(set, set.questions, scoreSetID = set.scoreKey), Screen.OfficialExam) }
                )

                Screen.ConstitutionAI -> ConstitutionAiScreen(
                    repo = repo,
                    onBack = { screen = Screen.Tests },
                    onStart = { set -> screen = Screen.Quiz(repo.newSession(set, set.questions, scoreSetID = set.scoreKey), Screen.ConstitutionAI) }
                )

                Screen.Favorites -> FavoritesScreen(repo,
                    onOpen = { screen = Screen.FavoriteGroup(it) }
                )

                is Screen.FavoriteGroup -> FavoriteGroupScreen(
                    repo = repo,
                    setID = s.setID,
                    onBack = { screen = Screen.Favorites },
                    onStart = { set, qs ->
                        screen = Screen.Quiz(
                            repo.newSession(set, qs, scoreSetID = null),
                            Screen.FavoriteGroup(s.setID)
                        )
                    }
                )

                Screen.Wrongs -> WrongsScreen(repo,
                    onOpen = { screen = Screen.WrongGroup(it) }
                )

                is Screen.WrongGroup -> WrongGroupScreen(
                    repo = repo,
                    setID = s.setID,
                    onBack = { screen = Screen.Wrongs },
                    onStart = { set, qs ->
                        screen = Screen.Quiz(
                            repo.newSession(set, qs, scoreSetID = null, wrongReviewSetID = s.setID),
                            Screen.WrongGroup(s.setID)
                        )
                    }
                )

                Screen.Saved -> SavedTestsScreen(repo,
                    onResume = { saved ->
                        repo.resumeSession(saved)?.let {
                            screen = Screen.Quiz(it, Screen.Saved)
                        }
                    }
                )

                is Screen.Quiz -> QuizScreen(
                    repo = repo,
                    session = s.session,
                    onExit = { screen = s.returnTo },
                    onFinished = { title, correct, wrong, total ->
                        repo.finishSession(s.session)
                        screen = Screen.Result(title, correct, wrong, total, s.returnTo)
                    }
                )

                is Screen.Result -> ResultScreen(
                    title = s.title,
                    correct = s.correct,
                    wrong = s.wrong,
                    total = s.total,
                    onDone = { screen = s.returnTo }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    repo: StudyRepository,
    onTests: () -> Unit,
    onSaved: () -> Unit,
    onWrongs: () -> Unit,
    onCategory: (StudyCategory) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Samsung tablet portrait/landscape için telefon ölçülerinden bağımsız yerleşim.
        val tablet = maxWidth >= 600.dp
        val wideTablet = maxWidth >= 900.dp
        val side = if (tablet) 28.dp else 18.dp
        val gap = if (tablet) 20.dp else 14.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = side, end = side, top = if (tablet) 24.dp else 18.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(if (tablet) 30.dp else 24.dp))
                        .background(HeroBrush)
                        .padding(if (tablet) 28.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tablet) 18.dp else 12.dp)
                ) {
                    Text(
                        "SINAV MERKEZİ",
                        color = Color.White.copy(alpha = .82f),
                        fontWeight = FontWeight.Bold,
                        fontSize = if (tablet) 14.sp else 12.sp
                    )
                    Text(
                        "İlerlemeni tek ekranda takip et.",
                        color = Color.White,
                        fontSize = if (tablet) 32.sp else 26.sp,
                        fontWeight = FontWeight.Black
                    )

                    if (tablet) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(Modifier.weight(1f)) { Metric("${repo.stats.answered}", "Çözülen") }
                            Box(Modifier.weight(1f)) { Metric("${repo.stats.correct}", "Doğru") }
                            Box(Modifier.weight(1f)) { Metric("${repo.stats.wrong}", "Yanlış") }
                            Box(Modifier.weight(1f)) { Metric("${repo.wrongCount}", "Kayıtlı yanlış") }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { Metric("${repo.stats.answered}", "Çözülen") }
                                Box(Modifier.weight(1f)) { Metric("${repo.stats.correct}", "Doğru") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { Metric("${repo.stats.wrong}", "Yanlış") }
                                Box(Modifier.weight(1f)) { Metric("${repo.wrongCount}", "Kayıtlı yanlış") }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (tablet) 14.dp else 9.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QuickButton("Testler", "▶", Indigo, Modifier.weight(1f), onTests)
                    QuickButton("Devam", "↻", Mint, Modifier.weight(1f), onSaved)
                    QuickButton("Yanlışlar", "!", Rose, Modifier.weight(1f), onWrongs)
                }
            }

            item {
                Text(
                    "Çalışma alanları",
                    fontSize = if (tablet) 23.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (tablet) 4.dp else 0.dp)
                )
            }

            // İç içe LazyGrid yerine normal satırlar: tablet ana ekranda kesilme,
            // sıkışma ve gereksiz büyük boşluk oluşmasını engeller.
            val categories = StudyCategory.entries.toList()
            val columns = if (wideTablet) 3 else if (tablet) 2 else 1
            val rows = categories.chunked(columns)
            items(rows.size) { rowIndex ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (tablet) 14.dp else 10.dp)
                ) {
                    rows[rowIndex].forEach { cat ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = if (tablet) 126.dp else 104.dp)
                                .clickable { onCategory(cat) },
                            shape = RoundedCornerShape(if (tablet) 22.dp else 18.dp)
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(if (tablet) 20.dp else 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(cat.iconText, fontSize = if (tablet) 28.sp else 24.sp, color = Indigo)
                                Text(cat.label, fontWeight = FontWeight.Bold, fontSize = if (tablet) 17.sp else 15.sp)
                                val count = repo.sets.filter { it.category == cat }.sumOf { it.questions.size }
                                Text("$count soru", color = Color.Gray, fontSize = if (tablet) 14.sp else 13.sp)
                            }
                        }
                    }
                    repeat(columns - rows[rowIndex].size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = .12f))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Text(label, color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
    }
}

@Composable
private fun QuickButton(title: String, icon: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable { onClick() }, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(icon, fontSize = 24.sp, color = color, fontWeight = FontWeight.Bold)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TestsScreen(
    repo: StudyRepository,
    onOpen: (QuizSet) -> Unit,
    onOfficial: () -> Unit,
    onAI: () -> Unit
) {
    PageHeader("Testler")
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 58.dp),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onOfficial), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("🎯 6 Eylül Gerçek Sınav Provası", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("50 soru • yasa ağırlıklı • Tarih + Coğrafya + Genel Kültür toplam 10", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onAI), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("AI • Tam Anayasa", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("anayasa-4 kaynağı • geçici maddeler hariç • bozuk aday atlanır, üretim devam eder", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        val gkEkSets = repo.sets.filter { it.id.startsWith("gkek-") }
        if (gkEkSets.isNotEmpty()) {
            item { Text("Genel Kültür EK", fontWeight = FontWeight.Bold, color = Indigo, modifier = Modifier.padding(top = 8.dp)) }
            items(gkEkSets, key = { it.id }) { set -> TestSetCard(repo, set, onOpen) }
        }

        StudyCategory.entries.forEach { cat ->
            val sets = repo.sets.filter { it.category == cat && !it.id.startsWith("gkek-") }
            if (sets.isNotEmpty()) {
                item { Text(cat.label, fontWeight = FontWeight.Bold, color = Indigo, modifier = Modifier.padding(top = 8.dp)) }
                items(sets, key = { it.id }) { set ->
                    TestSetCard(repo, set, onOpen)
                }
            }
        }
    }
}

@Composable
private fun CategoryScreen(repo: StudyRepository, category: StudyCategory, onBack: () -> Unit, onOpen: (QuizSet) -> Unit) {
    BackHandler(onBack = onBack)
    Column {
        TopBar(category.label, onBack)
        LazyColumn(
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(repo.sets.filter { it.category == category }, key = { it.id }) {
                TestSetCard(repo, it, onOpen)
            }
        }
    }
}

@Composable
private fun TestSetCard(repo: StudyRepository, set: QuizSet, onOpen: (QuizSet) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(set) },
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Indigo.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(set.iconText, color = Indigo, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(set.title, fontWeight = FontWeight.Bold)
                Text(set.subtitle, fontSize = 12.sp, color = Color.Gray)
                repo.lastScores[set.scoreKey]?.let {
                    Text("Son test: ${it.correct}/${it.total} • %${it.percent}", fontSize = 12.sp, color = Mint, fontWeight = FontWeight.Bold)
                } ?: Text("Henüz sonuç yok", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun OfficialExamScreen(repo: StudyRepository, onBack: () -> Unit, onStart: (QuizSet) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var set by remember { mutableStateOf(repo.buildOfficialExam()) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TopBar("6 Eylül Gerçek Sınav Provası", onBack) }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("50 soru • yasa ağırlıklı", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("Kıbrıs Tarihi 2 + Kıbrıs Coğrafyası 2 + Genel Kültür 2 + Genel Kültür EK 4 = toplam 10 soru.", color = Color.Gray)
                    Text("Anayasa 10 • Sivil Savunma 6 • Personel 6 • Sığınak 5 • Teşkilat/Donatım 6 • Atama/Disiplin 4 • Afet 3", fontSize = 13.sp)
                    Button(onClick = { onStart(set) }, modifier = Modifier.fillMaxWidth()) { Text("50 Soruluk Provayı Başlat") }
                    OutlinedButton(onClick = { printExamPdf(context, set) }, modifier = Modifier.fillMaxWidth()) { Text("PDF Çıkar / Yazdır") }
                    TextButton(onClick = { set = repo.buildOfficialExam() }, modifier = Modifier.fillMaxWidth()) { Text("Yeni 50 Soru Oluştur") }
                }
            }
        }
    }
}

@Composable
private fun ConstitutionAiScreen(repo: StudyRepository, onBack: () -> Unit, onStart: (QuizSet) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(10) }
    var difficulty by remember { mutableStateOf("Zor") }
    var status by remember { mutableStateOf("Geçici maddeler kaynak havuzuna alınmaz.") }
    var busy by remember { mutableStateOf(false) }
    var generated by remember { mutableStateOf<QuizSet?>(null) }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TopBar("AI • Tam Anayasa", onBack) }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔒 Geçici Maddeler Kesin Hariç", fontWeight = FontWeight.Black, color = Indigo)
                    Text("Bir aday soru/yanıt bozuk gelirse o aday atlanır. Önceki başarılı sorular korunur ve üretim yeni soruyla devam eder.", color = Color.Gray, fontSize = 13.sp)
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("OpenAI API anahtarı") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5,10,15,20).forEach { n ->
                            FilterChip(selected = count==n, onClick={count=n}, label={Text("$n")})
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Kolay","Orta","Zor").forEach { d ->
                            FilterChip(selected = difficulty==d, onClick={difficulty=d}, label={Text(d)})
                        }
                    }
                    Button(
                        enabled = !busy && key.isNotBlank(),
                        onClick = {
                            busy=true; generated=null
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        ConstitutionAiGenerator.generate(context, key, count, difficulty) { msg ->
                                            // Main thread status update below per round isn't required for correctness.
                                        }
                                    }
                                    status=result.note
                                    if (result.questions.isNotEmpty()) {
                                        generated=QuizSet("ai-anayasa","AI • Tam Anayasa","${result.questions.size} doğrulanmış soru",StudyCategory.REAL,result.questions,"AI")
                                    }
                                } catch(e:Exception) { status=e.message ?: "Üretim hatası" }
                                busy=false
                            }
                        },
                        modifier=Modifier.fillMaxWidth()
                    ){ Text(if(busy) "Üretim devam ediyor…" else "Soru Üret") }
                    Text(status, fontSize = 12.sp, color = if(generated!=null) Mint else Color.Gray)
                    generated?.let { g ->
                        Button(onClick={onStart(g)}, modifier=Modifier.fillMaxWidth()) { Text("${g.questions.size} Soruyu Çöz") }
                    }
                }
            }
        }
    }
}

private fun printExamPdf(context: Context, set: QuizSet) {
    val letters = "ABCDE"
    val body = buildString {
        append("<html><head><meta charset='utf-8'><style>@page{size:A4;margin:14mm}body{font-family:sans-serif;color:#222}h1{font-size:22px}.q{page-break-inside:avoid;margin:0 0 22px}.opt{margin:5px 0 0 18px}.key{page-break-before:always}</style></head><body>")
        append("<h1>${set.title}</h1><p><b>50 soru</b> • yasa ağırlıklı • Tarih + Coğrafya + Genel Kültür = 10</p>")
        val key = mutableListOf<String>()
        set.questions.forEachIndexed { i,q ->
            append("<div class='q'><b>${i+1}. ${android.text.Html.escapeHtml(q.stem)}</b>")
            val order=q.options.indices.shuffled()
            order.forEachIndexed { di,oi ->
                append("<div class='opt'><b>${letters[di]}.</b> ${android.text.Html.escapeHtml(q.options[oi])}</div>")
                if(oi==q.correctIndex) key += "${i+1}. ${letters[di]}"
            }
            append("</div>")
        }
        append("<div class='key'><h1>Cevap Anahtarı</h1><p>${key.joinToString(" &nbsp; ")}</p></div></body></html>")
    }
    val web = WebView(context)
    web.loadDataWithBaseURL(null, body, "text/html", "UTF-8", null)
    web.webViewClient = object : android.webkit.WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            val pm=context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print("Sivil Savunma - ${set.title}", web.createPrintDocumentAdapter(set.title), PrintAttributes.Builder().build())
        }
    }
}

@Composable
private fun SetDetailScreen(
    repo: StudyRepository,
    set: QuizSet,
    onBack: () -> Unit,
    onStart: (Int) -> Unit
) {
    var count by remember(set.id) { mutableIntStateOf(minOf(10, set.questions.size)) }
    BackHandler(onBack = onBack)

    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { TopBar(set.title, onBack) }

        item {
            Box(
                Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(28.dp))
                    .background(HeroBrush).padding(24.dp)
            ) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(set.iconText, color = Color.White, fontSize = 32.sp)
                    Text(set.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("${set.questions.size} soru", color = Color.White.copy(alpha = .9f), fontWeight = FontWeight.Bold)
                }
            }
        }

        repo.lastScores[set.scoreKey]?.let { last ->
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Son çözdüğün test", color = Color.Gray, fontSize = 12.sp)
                            Text("${last.correct}/${last.total}", fontSize = 36.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("%${last.percent}\n${last.wrong} yanlış", color = Mint, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Soru sayısı", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 20, 30, set.questions.size).distinct().forEach { n ->
                            FilterChip(
                                selected = count == n,
                                onClick = { count = n },
                                label = { Text(if (n == set.questions.size) "Tümü" else "$n") }
                            )
                        }
                    }
                    Button(
                        onClick = { onStart(count) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Testi Başlat", modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(repo: StudyRepository, onOpen: (String) -> Unit) {
    val groups = repo.favorites.mapNotNull(repo::question).groupBy { it.setID }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tüm favoriler silinsin mi?") },
            text = { Text("Favoriler bölümündeki her şey tüm cihazlarda silinecek.") },
            confirmButton = {
                TextButton(onClick = { repo.clearAllFavorites(); confirmClear = false }) {
                    Text("Hepsini Sil", color = Rose)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } }
        )
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) { PageHeader("Favoriler") }
            if (groups.isNotEmpty()) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Hepsini Sil", color = Rose) }
            }
        }
        if (groups.isEmpty()) {
            EmptyState("Favori yok", "Sorulardaki kalp düğmesine dokun. Favoriler sen silmedikçe kalır.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                contentPadding = PaddingValues(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(groups.entries.toList(), key = { it.key }) { e ->
                    val title = e.value.firstOrNull()?.setTitle ?: e.key
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(e.key) },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("♥", color = Rose, fontSize = 24.sp)
                            Text(title, fontWeight = FontWeight.Bold)
                            Text("${e.value.size} favori soru", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteGroupScreen(
    repo: StudyRepository,
    setID: String,
    onBack: () -> Unit,
    onStart: (QuizSet, List<QuizQuestion>) -> Unit
) {
    BackHandler(onBack = onBack)
    val favItems = repo.favorites.mapNotNull(repo::question).filter { it.setID == setID }
    val sourceSet = repo.setFor(setID)
    val title = favItems.firstOrNull()?.setTitle ?: sourceSet?.title ?: "Favoriler"

    Column {
        TopBar(title, onBack)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, favItems.size).distinct().forEach { n ->
                Button(
                    onClick = {
                        if (favItems.isNotEmpty()) {
                            val set = sourceSet ?: QuizSet("fav-$setID", "Favoriler • $title", "${favItems.size} soru", StudyCategory.GENERAL, favItems, "♥")
                            onStart(set, favItems.shuffled().take(n.coerceAtMost(favItems.size)))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (n == favItems.size) "Tümü" else "$n Soru") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(favItems, key = { it.id }) { q ->
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.stem, fontWeight = FontWeight.SemiBold)
                            Text(q.correctAnswer, color = Color.Gray, fontSize = 12.sp)
                        }
                        TextButton(onClick = { repo.removeFavorite(q.id) }) { Text("Sil", color = Rose) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrongsScreen(repo: StudyRepository, onOpen: (String) -> Unit) {
    val groups = repo.wrongs.mapNotNull { (setID, ids) ->
        val set = repo.setFor(setID) ?: return@mapNotNull null
        if (ids.isEmpty()) null else Triple(setID, set, ids.size)
    }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tüm yanlışlar silinsin mi?") },
            text = { Text("Yanlışlar bölümündeki her şey tüm cihazlarda silinecek.") },
            confirmButton = {
                TextButton(onClick = { repo.clearAllWrongs(); confirmClear = false }) {
                    Text("Hepsini Sil", color = Rose)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } }
        )
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) { PageHeader("Yanlışlar") }
            if (groups.isNotEmpty()) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Hepsini Sil", color = Rose) }
            }
        }
        if (groups.isEmpty()) {
            EmptyState("Yanlış soru yok", "Yanlış yaptığın sorular burada bölüm bölüm birikir.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                contentPadding = PaddingValues(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(groups, key = { it.first }) { (setID, set, count) ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(setID) },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("!", color = Rose, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text(set.title, fontWeight = FontWeight.Bold)
                            Text("$count yanlış soru", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrongGroupScreen(
    repo: StudyRepository,
    setID: String,
    onBack: () -> Unit,
    onStart: (QuizSet, List<QuizQuestion>) -> Unit
) {
    BackHandler(onBack = onBack)
    val set = repo.setFor(setID)
    val wrongItems = repo.wrongQuestions(setID)

    Column {
        TopBar(set?.title ?: "Yanlışlar", onBack)
        Card(Modifier.padding(16.dp), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bu bölümün yanlış testini tamamen bitirdiğinde bu kayıt temizlenir.", color = Color.Gray)
                Button(
                    onClick = { if (set != null && wrongItems.isNotEmpty()) onStart(set, wrongItems) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Yanlışları Çöz") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(wrongItems, key = { it.id }) { q ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Text(q.stem, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SavedTestsScreen(repo: StudyRepository, onResume: (SavedTest) -> Unit) {
    Column {
        PageHeader("Kayıtlı Testler")
        if (repo.savedTests.isEmpty()) {
            EmptyState("Kayıtlı test yok", "Test çözerken Kaydet'e bas. Aynı soru ve aynı şık düzeninden devam edersin.")
        } else {
            LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(repo.savedTests, key = { it.id }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onResume(item) },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold)
                                Text("${item.currentIndex + 1}. sorudan devam • ${item.correct} doğru • ${item.wrong} yanlış", color = Color.Gray, fontSize = 12.sp)
                                Text(formatDate(item.savedAt), color = Color.Gray, fontSize = 11.sp)
                            }
                            TextButton(onClick = { repo.deleteSaved(item.id) }) { Text("Sil", color = Rose) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizScreen(
    repo: StudyRepository,
    session: QuizSession,
    onExit: () -> Unit,
    onFinished: (String, Int, Int, Int) -> Unit
) {
    var revision by remember { mutableIntStateOf(0) }
    val rq = session.current
    val q = rq.source
    revision // observe state so Compose refreshes after local mutations

    BackHandler(onBack = onExit)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onExit) { Text("Çık") }
            Column(Modifier.weight(1f)) {
                Text(session.set.title, fontWeight = FontWeight.Bold, maxLines = 1)
                LinearProgressIndicator(
                    progress = { (session.index + 1).toFloat() / session.runtimeQuestions.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("${session.index + 1}/${session.runtimeQuestions.size}", fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { repo.addFavorite(q.id); revision++ },
                enabled = q.id !in repo.favorites
            ) {
                Text(if (q.id in repo.favorites) "♥" else "♡", color = Rose, fontSize = 24.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Soru ${q.number}", color = Indigo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(q.stem, fontWeight = FontWeight.Bold, fontSize = 20.sp)

                        rq.optionOrder.forEachIndexed { displayIndex, originalIndex ->
                            val text = q.options[originalIndex]
                            val isElim = originalIndex in rq.eliminatedOriginalIndices
                            val isCorrect = session.answered && displayIndex == rq.displayedCorrectIndex
                            val isWrong = session.answered && session.selectedDisplayIndex == displayIndex && displayIndex != rq.displayedCorrectIndex

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val bg = when {
                                    isCorrect -> Mint.copy(alpha = .18f)
                                    isWrong -> Rose.copy(alpha = .16f)
                                    session.selectedDisplayIndex == displayIndex -> Indigo.copy(alpha = .14f)
                                    else -> Color(0xFFF2F3F7)
                                }

                                Row(
                                    Modifier.weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(bg)
                                        .clickable(enabled = !session.answered && !isElim) {
                                            session.selectedDisplayIndex = displayIndex
                                            revision++
                                        }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(('A'.code + displayIndex).toChar().toString(), fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text,
                                        modifier = Modifier.weight(1f),
                                        color = if (isElim) Color.Gray else Color.Unspecified,
                                        textDecoration = if (isElim) TextDecoration.LineThrough else null
                                    )
                                }

                                if (!session.answered) {
                                    TextButton(onClick = {
                                        if (isElim) rq.eliminatedOriginalIndices.remove(originalIndex)
                                        else if (rq.eliminatedOriginalIndices.size < q.options.size - 1) {
                                            rq.eliminatedOriginalIndices.add(originalIndex)
                                            if (session.selectedDisplayIndex == displayIndex) session.selectedDisplayIndex = null
                                        }
                                        revision++
                                    }) {
                                        Text(if (isElim) "Geri Al" else "Ele")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (session.answered) {
                item {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val ok = session.selectedDisplayIndex == rq.displayedCorrectIndex
                            Text(if (ok) "Doğru" else "Yanlış", color = if (ok) Mint else Rose, fontWeight = FontWeight.Bold)
                            Text("Doğru cevap: ${q.correctAnswer}", fontWeight = FontWeight.Bold)
                            val detail = q.constitutionFeedback.ifBlank { q.explanation }
                            if (detail.isNotBlank()) Text(detail, color = Color.Gray)
                            if (q.constitutionArticle.isNotBlank()) Text(q.constitutionArticle, color = Indigo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { repo.saveSession(session); revision++ },
                modifier = Modifier.weight(1f)
            ) { Text("Kaydet") }

            Button(
                onClick = {
                    if (!session.answered) {
                        val selected = session.selectedDisplayIndex ?: return@Button
                        val ok = selected == rq.displayedCorrectIndex
                        if (ok) session.correct++ else session.wrong++
                        repo.recordAnswer(q, ok)
                        session.wrongReviewSetID?.let { reviewSetID ->
                            repo.resolveWrongImmediately(q, reviewSetID)
                        }
                        session.answered = true
                    } else if (session.index + 1 >= session.runtimeQuestions.size) {
                        onFinished(session.set.title, session.correct, session.wrong, session.runtimeQuestions.size)
                        return@Button
                    } else {
                        session.index++
                        session.selectedDisplayIndex = null
                        session.answered = false
                    }
                    revision++
                },
                enabled = session.answered || session.selectedDisplayIndex != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (!session.answered) "Cevapla"
                    else if (session.index + 1 >= session.runtimeQuestions.size) "Bitir"
                    else "Sonraki"
                )
            }
        }
    }
}

@Composable
private fun ResultScreen(title: String, correct: Int, wrong: Int, total: Int, onDone: () -> Unit) {
    val answered = correct + wrong
    val pct = if (answered == 0) 0 else ((correct.toDouble() / answered) * 100).toInt()

    Box(Modifier.fillMaxSize().background(HeroBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (pct >= 70) "🏆" else "▥", fontSize = 52.sp)
            Text("%$pct", color = Color.White, fontSize = 68.sp, fontWeight = FontWeight.Black)
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                ResultMetric("$correct", "Doğru")
                ResultMetric("$wrong", "Yanlış")
                ResultMetric("$total", "Toplam")
            }
            Button(onClick = onDone) { Text("Testlere Dön") }
        }
    }
}

@Composable
private fun ResultMetric(v: String, t: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(t, color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
    }
}

@Composable
private fun PageHeader(title: String) {
    Box(
        Modifier.fillMaxWidth().height(58.dp).background(Color.White).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(Color.White).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("←") }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color.Gray)
        }
    }
}

private fun formatDate(time: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(time))
