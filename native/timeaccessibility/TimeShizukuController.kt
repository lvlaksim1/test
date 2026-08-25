package __PACKAGE__.timeaccessibility

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class ShizukuState(val isRunning:Boolean,val isPermissionGranted:Boolean)
data class ShizukuCommandOutcome(val isSuccess:Boolean,val detail:String)
object TimeShizukuController {
 private const val REQUEST_CODE=7201;private const val SERVICE_TAG="time-cycler-direct-time-v2";private const val SERVICE_VERSION=7;private const val SERVICE_PROCESS_SUFFIX="timecycler"
 private val lock=Any();private val mainHandler=Handler(Looper.getMainLooper());private val executor=Executors.newSingleThreadExecutor();private var remoteService:ITimeShizukuService?=null;private var isBinding=false;private var pendingRequest:Pair<(ITimeShizukuService)->String,(ShizukuCommandOutcome)->Unit>?=null
 fun state():ShizukuState{val r=runCatching{Shizuku.pingBinder()}.getOrDefault(false);return ShizukuState(r,r&&runCatching{Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED}.getOrDefault(false))}
 fun requestPermission():Boolean{val c=state();if(!c.isRunning)return false;if(c.isPermissionGranted)return true;if(runCatching{Shizuku.shouldShowRequestPermissionRationale()}.getOrDefault(true))return false;return runCatching{Shizuku.requestPermission(REQUEST_CODE);false}.getOrDefault(false)}
 fun applyTime(c:Context,t:Long,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.applyTime(t)},cb);fun setAutomaticTime(c:Context,e:Boolean,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.setAutomaticTime(e)},cb);fun listOpenApps(c:Context,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.listOpenApps()},cb);fun inspectApp(c:Context,p:String,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.inspectApp(p,c.packageName)},cb);fun invokeElement(c:Context,p:String,b:String,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.invokeElement(p,b,c.packageName)},cb);fun diagnosePackage(c:Context,p:String,cb:(ShizukuCommandOutcome)->Unit)=execute(c,{it.diagnosePackage(p)},cb)
 private fun execute(c:Context,cmd:(ITimeShizukuService)->String,cb:(ShizukuCommandOutcome)->Unit){if(!state().isPermissionGranted){deliver(cb,ShizukuCommandOutcome(false,"Shizuku не запущен или доступ не выдан."));return};var refused=false;val existing=synchronized(lock){val s=remoteService;if(s==null){if(pendingRequest!=null)refused=true else{pendingRequest=cmd to cb;if(!isBinding){isBinding=true;bind(c)}}};s};if(refused){deliver(cb,ShizukuCommandOutcome(false,"Предыдущая команда Shizuku ещё выполняется."));return};if(existing!=null)invoke(existing,cmd,cb)}
 private fun bind(c:Context){runCatching{Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(c,TimeShizukuUserService::class.java)).tag(SERVICE_TAG).version(SERVICE_VERSION).processNameSuffix(SERVICE_PROCESS_SUFFIX).daemon(false),connection)}.onFailure{f->val r=synchronized(lock){isBinding=false;pendingRequest.also{pendingRequest=null}};r?.second?.let{deliver(it,ShizukuCommandOutcome(false,"Не удалось подключить Shizuku: ${f.message.orEmpty()}"))}}}
 private val connection=object:ServiceConnection{override fun onServiceConnected(n:ComponentName?,b:IBinder?){val r=synchronized(lock){remoteService=ITimeShizukuService.Stub.asInterface(b);isBinding=false;pendingRequest.also{pendingRequest=null}};if(remoteService==null){r?.second?.let{deliver(it,ShizukuCommandOutcome(false,"Shizuku не вернул службу."))};return};r?.let{invoke(remoteService!!,it.first,it.second)}};override fun onServiceDisconnected(n:ComponentName?){synchronized(lock){remoteService=null}}}
 private fun invoke(s:ITimeShizukuService,cmd:(ITimeShizukuService)->String,cb:(ShizukuCommandOutcome)->Unit){executor.execute{val d=runCatching{cmd(s)}.getOrElse{"ОШИБКА: ${it.message.orEmpty()}"};deliver(cb,ShizukuCommandOutcome(d.startsWith("OK:"),d))}}
 private fun deliver(cb:(ShizukuCommandOutcome)->Unit,o:ShizukuCommandOutcome){mainHandler.post{cb(o)}}
}
