package staff.android.frame

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity:ComponentActivity(){
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)ViewModelProvider(this)[CaseModel::class.java].refresh()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val model=ViewModelProvider(this)[CaseModel::class.java]
        setContent{ProductTheme{val state by model.state.collectAsStateWithLifecycle();CaseScreen(state,model)}}
    }
}
val BodyFont=FontFamily(Font(R.font.google_sans_regular),Font(R.font.google_sans_bold,FontWeight.Bold))
val HeadingFont=FontFamily(Font(R.font.sora_bold,FontWeight.Bold))
@Composable fun ProductTheme(content:@Composable ()->Unit){
    val scheme=if(isSystemInDarkTheme())darkColorScheme(
        primary=Color(0xFFB3D2E5),onPrimary=Color(0xFF173E54),background=Color(0xFF142129),onBackground=Color(0xFFF0F3EE),
        surface=Color(0xFF20333F),onSurface=Color(0xFFF0F3EE),surfaceVariant=Color(0xFF324C5A),onSurfaceVariant=Color(0xFFCBDDE7),
        secondaryContainer=Color(0xFF31566B),onSecondaryContainer=Color(0xFFD5EBF8),outline=Color(0xFF99ADA0),outlineVariant=Color(0xFF526659))
    else lightColorScheme(
        primary=Color(0xFF3A5D72),onPrimary=Color(0xFFFFFFFF),background=Color(0xFFF1F4F5),onBackground=Color(0xFF22343D),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF22343D),surfaceVariant=Color(0xFFE0E8EC),onSurfaceVariant=Color(0xFF536E7C),
        secondaryContainer=Color(0xFFDBE9F1),onSecondaryContainer=Color(0xFF294E62),outline=Color(0xFF6E8A99),outlineVariant=Color(0xFFC5D5DD))
    val defaults=Typography()
    MaterialTheme(scheme,typography=defaults.copy(bodyLarge=defaults.bodyLarge.copy(fontFamily=BodyFont),
        bodyMedium=defaults.bodyMedium.copy(fontFamily=BodyFont),titleMedium=defaults.titleMedium.copy(fontFamily=BodyFont),
        labelLarge=defaults.labelLarge.copy(fontFamily=BodyFont)),content=content)
}
@Composable fun CaseScreen(state: CaseState, model: CaseModel) {
    var lab by rememberSaveable { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val diagnostics = remember { CameraDiagnostics() }
    BackHandler(enabled = lab) { lab = false }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = SystemClock.elapsedRealtime() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AppChrome("Frame") {
                TextButton(onClick = model::refresh, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("refresh")) { Text("Refresh") }
                TextButton(onClick = { lab = !lab }, modifier = Modifier.testTag("lab-tab")) { Text(if (lab) "Back" else "Lab") }
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            val cameraViewportHeight = maxHeight
            Column(Modifier.widthIn(max = 820.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy"))
                state.problem?.let { InfoCard("Connection and recovery", it) }
                state.pending?.let {
                    InfoCard("Waiting for confirmation", "Your change is saved. Retry the same request before making another change.")
                    Button(onClick = model::retry, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("retry")) { Text("Retry saved request") }
                }
                state.outcome?.takeIf { it.has("reason") }?.let { InfoCard("Request result", it.getString("reason")) }
                Product(state, model, 0L, diagnostics, cameraViewportHeight)

            }
        }
    }
    if (lab) AlertDialog(onDismissRequest = { lab = false }, title = { Text("Camera laboratory") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ABOUT)
            Text("Analyzed ${diagnostics.frames} frames · mean luminance ${diagnostics.luma} / 255", modifier = Modifier.testTag("analysis"))
            state.data?.let { Text(it.getJSONArray("jobs").toString(2), style = MaterialTheme.typography.bodySmall) }
            Text("The camera surface stays mounted while this panel is open, so an in-flight capture is not abandoned. Captures remain private to this app. See the README before resetting the ten-image collection.")
        }
    }, confirmButton = { TextButton(onClick = { lab = false }, modifier = Modifier.testTag("close-lab")) { Text("Return to camera") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About Frame") }, text = { Text(ABOUT) }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
}
@Composable fun InfoCard(title:String,body:String){
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(title,fontWeight=FontWeight.Bold);Text(body,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable fun SectionTitle(title:String){Text(title,fontFamily=HeadingFont,fontSize=27.sp,lineHeight=34.sp)}
fun ready(state:CaseState)=!state.busy&&!state.storageBlocked&&state.pending==null

@Composable
private fun AppChrome(title: String, statusInsets: Boolean = true, actions: @Composable () -> Unit) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints((if (statusInsets) Modifier.statusBarsPadding() else Modifier).fillMaxWidth().padding(horizontal = 16.dp)) {
        val needed = (title.length * 13 + if (title == "Fieldnotes") 290 else 190).dp * fontScale
        if (maxWidth < needed) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                actions()
            }
        }
    }
}
