package staff.android.frame
import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class ProductJourneyTest {
 @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK,Manifest.permission.CAMERA)
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun completeCaseJourney(){try{
            waitTag("capture")
            compose.waitUntil(30000){!compose.onNodeWithTag("capture").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
            tap("lab-tab")
            compose.waitUntil(30000){compose.onAllNodesWithTag("analysis").fetchSemanticsNodes().any{node->Regex("Analyzed [1-9][0-9]* frames").containsMatchIn(node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString())}}
            tap("close-lab")
            tap("capture");waitTag("saved-image");waitTag("register");tap("register");waitReady()
            assertEquals(1,current().getJSONArray("jobs").length())
            val file=File(context().filesDir,"captures").listFiles()!!.first{it.extension=="jpg"}
            assertEquals(true,file.length()>0)
            tap("cancel");waitTag("retry-job");tap("retry-job");waitReady()
            val row=current().getJSONArray("jobs").getJSONObject(0)
            assertEquals(2,row.getInt("generation"))
            assertEquals(false,command("finish",JSONObject().put("assetId",row.getString("assetId")).put("generation",1)).getJSONObject("outcome").getBoolean("accepted"))
 }finally{request("/__control","{}")}}
 private fun context()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun current()=request("/view").getJSONObject("data")
 private fun command(action:String,args:JSONObject)=request("/commands",JSONObject().put("id",UUID.randomUUID().toString()).put("action",action).put("args",args).toString())
 private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("refresh", "lab-tab", "close-lab")) node.performClick() else node.performScrollTo().performClick()
    }
 private fun waitTag(tag:String){compose.waitUntil(45000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
 private fun waitReady(){compose.waitUntil(45000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
 private fun request(path:String,body:String?=null):JSONObject{
  val connection=URL("http://10.0.2.2:8923$path").openConnection() as HttpURLConnection
  connection.connectTimeout=10000;connection.readTimeout=10000
  if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
  return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
 }
}
