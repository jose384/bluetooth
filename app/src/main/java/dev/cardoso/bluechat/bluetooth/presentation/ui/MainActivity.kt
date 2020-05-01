package dev.cardoso.bluechat.bluetooth.presentation.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.cardoso.bluechat.R
import dev.cardoso.bluechat.bluetooth.*
import dev.cardoso.bluechat.bluetooth.framework.ChatService
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.lang.Math.random
import kotlin.random.Random

open class MainActivity : AppCompatActivity() {
    private val PICK_IMAGE = 100
    private val WRITE_EX = 102
    var imagename: Uri?=null
    var imagearray: ByteArray?=null
    var escritura=false
    val chars = "abcdefghijklmnopqrstuvwxyz".toCharArray()
    val sb = StringBuilder(20)
    //--- Properties for bluetooth
    private val REQUEST_CONNECT_DEVICE_SECURE = 1
    private val REQUEST_CONNECT_DEVICE_INSECURE = 2
    private val REQUEST_ENABLE_BT = 3

    private var connectedDeviceName: String? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var chatService: ChatService? = null

    private var chatArrayAdapter: ArrayAdapter<String>? = null
    private var outStringBuffer=StringBuffer("")

    private val handler = Handler(Handler.Callback { msg ->
        when (msg.what) {
            MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                ChatService.STATE_CONNECTED -> {
                    setStatus(getString(
                        R.string.title_connected_to,
                        connectedDeviceName))
                    chatArrayAdapter!!.clear()
                }
                ChatService.STATE_CONNECTING -> setStatus(R.string.title_connecting)
                ChatService.STATE_LISTEN, ChatService.STATE_NONE -> setStatus(R.string.title_not_connected)
            } //--- End MESSAGE_STATE_CHANGE

            MESSAGE_WRITE -> {
                val writeBuf = msg.obj as ByteArray
                if(writeBuf.size<990){
                    val writeMessage = String(writeBuf)
                    chatArrayAdapter!!.add("YO:  $writeMessage")
                }
                else{
                    var imagebit = getImageBitmap(writeBuf)
                    imageImagen.setImageBitmap(imagebit)
                    imageImagen.visibility= View.VISIBLE
                    Toast.makeText(this, "Envio completado...",Toast.LENGTH_LONG).show()
                    etMain.isEnabled=true
                    val handler = Handler()
                    handler.postDelayed({
                        imageImagen.visibility= View.GONE }, 5000)
                }

            }
            MESSAGE_READ -> {
                val bytes= msg.arg1
                val readBuf = msg.obj as ByteArray
                if (bytes<990){
                    val readMessage = String(readBuf, 0, bytes )
                    chatArrayAdapter!!.add("$connectedDeviceName:  $readMessage")
                }
                else{
                    var imagebit = getImageBitmap(readBuf)
                    imageImagen.setImageBitmap(imagebit)
                    imageImagen.visibility= View.VISIBLE
                    if (escritura){
                        for (i in 0..19) {
                            val random = (0..25).random()
                            val c = chars[random]
                            sb.append(c)
                        }
                    }
                    val handler = Handler()
                    handler.postDelayed({
                        GuardarImg(imagebit!!,sb.toString())
                        imageImagen.visibility= View.GONE
                    Toast.makeText(this,"Imagen almacenada en Pictures..",Toast.LENGTH_LONG).show()
                        sb.clear()
                    }, 5000)
                }

            }
            MESSAGE_DEVICE_NAME -> {
                connectedDeviceName = msg.data.getString(DEVICE_NAME)
                Toast.makeText(applicationContext,
                    "Connected to $connectedDeviceName",
                    Toast.LENGTH_SHORT).show()

            }
            MESSAGE_TOAST -> {
                Toast.makeText(this,msg.data.getString(TOAST), Toast.LENGTH_LONG).show()
            }
        }
        false
    })

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindEventHandler()
        imageImagen.visibility= View.GONE
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED){
                val permisos= arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                requestPermissions(permisos,WRITE_EX)
            }
            else{
                escritura=true
            }
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        btnImagen.setOnClickListener{
            openGallery()
        }

    }

    private fun openGallery() {
        var gallery = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
        gallery.type = "image/*"
        startActivityForResult(gallery, PICK_IMAGE)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CONNECT_DEVICE_SECURE -> if (resultCode == Activity.RESULT_OK) {
                connectDevice(data!!, true)
            }
            REQUEST_CONNECT_DEVICE_INSECURE -> if (resultCode == Activity.RESULT_OK) {
                connectDevice(data!!, false)
            }
            REQUEST_ENABLE_BT -> if (resultCode == Activity.RESULT_OK) {
                setupChat()
            } else {
                Toast.makeText(this,
                    R.string.bt_not_enabled_leaving,
                    Toast.LENGTH_SHORT).show()
                finish()
            }
            PICK_IMAGE -> if (resultCode == Activity.RESULT_OK){
                imagename = data!!.data
                imageImagen.setImageURI(imagename)
                imageImagen.visibility= View.VISIBLE
                btnSend.isEnabled=true
                etMain.isEnabled=false
            }
            else{
                Toast.makeText(this, "Carga cancelada",Toast.LENGTH_LONG).show()
                imageImagen.visibility= View.GONE
                imagename = null
                imageImagen.setImageURI(imagename)
                btnSend.isEnabled=false
                etMain.isEnabled=true
            }

        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when(requestCode){
            WRITE_EX ->{
                if(grantResults[0]==PackageManager.PERMISSION_GRANTED){
                    escritura=true
                }
                else{
                    Toast.makeText(this,"Asigne los permisos",Toast.LENGTH_LONG).show()
                    val handler = Handler()
                    handler.postDelayed({
                        finish() }, 2000)
                }
            }
        }
    }
    fun GuardarImg(bitmap: Bitmap, name: String) {
        lateinit var img: OutputStream
       try {
           val direct= Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
           val fileImage = File(direct,name+".png")
           img = FileOutputStream(fileImage)
           bitmap.compress(Bitmap.CompressFormat.PNG, 90, img)
           img!!.close()
       } catch (e:IOException){
           Toast.makeText(this, "No se creó la imagen...", Toast.LENGTH_LONG).show()
       }
    }
    private fun bindEventHandler() {

        etMain.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable) {}

            override fun beforeTextChanged(s: CharSequence, start: Int,
                                           count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int,
                                       before: Int, count: Int) {
                btnSend.isEnabled= s.trim().length>0
            }
        })
        btnSend.setOnClickListener {
            if(imagename==null){
                val message = etMain.text.toString()
                val send = message.toByteArray()
                sendMessage(send)
            }
            else if (imagename != null){
               imagearray = imageToArray(imageImagen)
                sendMessage(imagearray!!)
            }
        }
    }
    private fun imageToArray(image: ImageView): ByteArray {
        val bitmap = (image.drawable as BitmapDrawable).bitmap
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)

        return stream.toByteArray()
    }
    private fun getImageBitmap(image: ByteArray): Bitmap? {
        var imagebit= BitmapFactory.decodeByteArray(image, 0, image.size)
        return imagebit
    }

    private fun connectDevice(data: Intent, secure: Boolean) {
        val address = data.extras!!.getString(
            DeviceListActivity.DEVICE_ADDRESS)
        val device = bluetoothAdapter!!.getRemoteDevice(address)
        chatService!!.connect(device, secure)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.option_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var serverIntent: Intent?
        when (item.itemId) {
            R.id.secure_connect_scan -> {
                serverIntent = Intent(this, DeviceListActivity::class.java)
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE)
                return true
            }
            R.id.insecure_connect_scan -> {
                serverIntent = Intent(this, DeviceListActivity::class.java)
                startActivityForResult(serverIntent,
                    REQUEST_CONNECT_DEVICE_INSECURE)
                return true
            }
            R.id.discoverable -> {
                ensureDiscoverable()
                return true
            }
        }
        return false
    }

    private fun ensureDiscoverable() {
        if (bluetoothAdapter!!.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            val discoverableIntent = Intent(
                BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(
                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(discoverableIntent)
        }
    }
    private fun sendMessage(message: ByteArray) {
        if (chatService!!.getState() != ChatService.STATE_CONNECTED) {
            Toast.makeText(this,
                R.string.not_connected, Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (message.isNotEmpty()) {
            imagename=null
            imageImagen.setImageURI(imagename)
            imageImagen.visibility= View.GONE
            val send = message
            chatService!!.write(send)
            outStringBuffer.setLength(0)
            etMain.setText(outStringBuffer)
        }
    }

    private fun setStatus(resId: Int) {
        val actionBar: ActionBar? = supportActionBar
        actionBar?.setSubtitle(resId)
    }

    private fun setStatus(subTitle: CharSequence) {
        val actionBar: ActionBar? = supportActionBar
        actionBar?.subtitle = subTitle
    }

    private fun setupChat() {
        chatArrayAdapter = ArrayAdapter(this,
            R.layout.message
        )
        lvMainChat.adapter = chatArrayAdapter
        chatService = ChatService(handler)
        outStringBuffer = StringBuffer("")
    }


    override fun onStart() {
        super.onStart()
        if (!bluetoothAdapter!!.isEnabled) {
            val enableIntent = Intent(
                BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
        } else {
            if (chatService == null) setupChat()
        }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        if (chatService != null) {
            if (chatService!!.getState() == ChatService.STATE_NONE) {
                chatService!!.start()
            }
        }
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (chatService != null) chatService!!.stop()
    }

}



