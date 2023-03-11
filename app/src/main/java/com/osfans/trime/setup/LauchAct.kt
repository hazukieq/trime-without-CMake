package com.osfans.trime.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.osfans.trime.R
import com.osfans.trime.util.SpvalueStorage
import com.permissionx.guolindev.PermissionX
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

class LauchAct : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lauch)


        if(!shouldSetup(this)){
             requestPermission()
        }
        else finish()
    }

    companion object {
        fun shouldSetup(context:Context):Boolean{
            var isAll=false
            val sputil=SpvalueStorage.getInstance(context)
            if (sputil != null) {
                isAll=sputil.getBooleanValue("isAll",false)
            }
            return isAll
        }

        fun setup(context:Context,string: String,boolean: Boolean){
            val sputil=SpvalueStorage.getInstance(context)
            if (sputil != null) {
                sputil.setBooleanValue(string,boolean)
            }
        }

    }

    private fun requestPermission(){
        val requestList=ArrayList<String>()
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M&& Build.VERSION.SDK_INT<= Build.VERSION_CODES.Q){
            requestList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }else if(Build.VERSION.SDK_INT> Build.VERSION_CODES.Q){
            //requestList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            //requestList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            requestList.add(Manifest.permission.SYSTEM_ALERT_WINDOW)
        }
        if(!requestList.isEmpty()){
            PermissionX.init(this)
                .permissions(requestList)
                .onExplainRequestReason{scope,deniedList->
                    //val msg=getString(R.string.external_storage_access_required)+"\n"+getString(R.string.alert_window_access_required)
                    val msg= "即将申请的权限是程序运行必须依赖权限。\n同文输入法 需访问手机存储、用于为您提供文字、符号、表情、语音等内容输入服务。悬浮窗权限用于显示输入法键盘。"

                    scope.showRequestReasonDialog(deniedList,msg,"同意","拒绝")
                }
                .request { allGranted, grantedList, deniedList ->
                    if (allGranted) {
                        setup(this,"isAll",true)

                        if(Hks2local()) {
                            ToastUtils.showShort(R.string.external_storage_permission_granted)
                            startActivity(Intent(this,SetupActivity::class.java))
                            finish()
                        }
                        else  finish()
                    } else {
                        ToastUtils.showShort(R.string.external_storage_permission_not_available)
                        ToastUtils.showShort(" $deniedList")
                        requestPermission()
                    }
                }
        }
    }


    fun unzipFile(dest:File,file:String){
        val hks=assets.open(file)
        val zip=ZipInputStream(hks.buffered())
        var entry=zip.nextEntry

        while (entry!=null){
            val current=File("$dest/${entry.name}")
            if(entry.isDirectory) continue//current.mkdirs()
            else{
                //current.parentFile?.mkdirs()
                Timber.tag("hs").i(entry.name)
                zip.buffered().copyTo(current.outputStream())
            }
            entry=zip.nextEntry
        }
        zip.closeEntry()
        hks.close()
    }

    fun extractFiles(dest:File,file:String,nonexistedList:List<String>){
        val hks=assets.open(file)
        val zip=ZipInputStream(hks.buffered())
        var entry=zip.nextEntry

        while (entry!=null){
            val current = File("$dest/${entry.name}")
            if(entry.name in nonexistedList) {
                if (entry.isDirectory) continue//current.mkdirs()
                else {
                    zip.buffered().copyTo(current.outputStream())
                }
            }
            entry = zip.nextEntry
        }

        zip.closeEntry()
        hks.close()
    }
    fun Hks2local():Boolean{
        var isDone=false
        val rootpath=Environment.getExternalStorageDirectory().toString()+"/rime"
        val destFile=File(rootpath)
        try {
            if(!destFile.exists()) {
                destFile.mkdir()
                unzipFile(destFile,"hks/rime.zip")
            }else{
                val assetsFiles= mutableListOf(
                    "default.custom.yaml","default.yaml",
                    "essay.txt",
                    "hakka.dict.yaml","hakka_giugiung.schema.yaml","hakka_mobilewords.dict.yaml",
                    "hakka_oftenwords.dict.yaml","hakka_places.dict.yaml","hakka_poems.dict.yaml",
                    "hakka_sougou_words.dict.yaml","hakka_tong.trime.yaml","hakka_tung.dict.yaml",
                    "hakka_tung.schema.yaml","hakka_tung_q.schema.yaml","hakka_zon.dict.yaml",
                    "ipa_yunlong.dict.yaml","ipa_yunlong.schema.yaml",
                    "luna_pinyin.dict.yaml","luna_pinyin.schema.yaml","luna_pinyin_simp.schema.yaml",
                    "symbols.yaml"
                )

                val existedfileNames:MutableList<String> = mutableListOf()
                val fileTree:FileTreeWalk=destFile.walk()
                fileTree.maxDepth(1)
                    .filter { it.isFile }
                    .filter { it.extension in listOf("yaml","txt") }
                    .forEach { existedfileNames.add(it.name) }
                existedfileNames.forEach(::println)


                val nonexistedFiles=existedfileNames.filter {
                    it !in assetsFiles
                }

                extractFiles(destFile,"hks/rime.zip",nonexistedFiles)
            }
            isDone=true
        }catch (e:Exception){
            e.printStackTrace()
            isDone=false
        }
        return isDone
    }
}