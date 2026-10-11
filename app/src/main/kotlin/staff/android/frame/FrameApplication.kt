package staff.android.frame
import android.app.Application
import java.io.File
class FrameApplication:Application(){
 override fun onCreate(){
  super.onCreate()
  // Process-start cleanup runs before any camera owner exists, never during Activity recreation.
  File(filesDir,"captures").listFiles()?.filter{it.name.matches(Regex("[0-9a-f-]{36}\\.partial"))}?.forEach{it.delete()}
 }
}
