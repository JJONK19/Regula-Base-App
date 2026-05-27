package com.regula.demo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.regula.demo.databinding.ActivityMainBinding
import com.regula.demo.fingerprint.BluetoothReaderHelper
import com.regula.demo.fingerprint.BluetoothReaderService
import com.regula.demo.fingerprint.util.BluetoothPermissionHelper.isPermissionsGranted
import com.regula.demo.fingerprint.util.BluetoothPermissionHelper.requestBlePermissions
import com.regula.demo.fingerprint.util.Constants
import com.regula.demo.fingerprint.util.Constants.CMD_GETIMAGE
import com.regula.demo.fingerprint.util.Constants.IMG288
import com.regula.demo.fingerprint.util.Constants.MESSAGE_STATE_CHANGE
import com.regula.demo.fingerprint.util.Constants.TOAST
import com.regula.documentreader.api.DocumentReader
import com.regula.common.ble.BLEWrapper
import com.regula.common.ble.RegulaBleService
import com.regula.documentreader.api.config.ScannerConfig
import com.regula.documentreader.api.completions.rfid.IRfidReaderCompletion
import com.regula.documentreader.api.enums.*
import com.regula.documentreader.api.errors.DocumentReaderException
import com.regula.documentreader.api.results.DocumentReaderResults
import com.regula.documentreader.api.results.authenticity.DocumentReaderIdentResult
import com.regula.facesdk.FaceSDK
import com.regula.facesdk.configuration.FaceCaptureConfiguration
import com.regula.facesdk.detection.request.OutputImageCrop
import com.regula.facesdk.detection.request.OutputImageParams
import com.regula.facesdk.enums.ImageType
import com.regula.facesdk.enums.OutputImageCropAspectRatio
import com.regula.facesdk.model.MatchFacesImage
import com.regula.facesdk.model.results.FaceCaptureResponse
import com.regula.facesdk.model.results.matchfaces.MatchFacesResponse
import com.regula.facesdk.model.results.matchfaces.MatchFacesSimilarityThresholdSplit
import com.regula.facesdk.request.MatchFacesRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    // ===== Document Reader fields =====
    private var bleManager: BLEWrapper? = null
    private var isBleServiceConnected = false
    private var rfidReading = false
    private var matchFaces = true

    // ===== Fingerprint fields =====
    private var mDeviceCmd: Byte = 0x00
    private var mIsWork = false
    private var mTimerTimeout: Timer? = null
    private var mTaskTimeout: TimerTask? = null
    private var mHandlerTimeout: Handler? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var imgSize = 0
    var mUpImageSize = 0
    private var mChatService: BluetoothReaderService? = null
    var mUpImage = ByteArray(73728)
    var mUpImage2 = ByteArray(73728)

    // ===== Results tracking for JSON export =====
    private var lastDocResults: DocumentReaderResults? = null
    private var lastFingerprintBitmap: Bitmap? = null
    private var lastLivePortraitBitmap: Bitmap? = null
    private var lastFaceSimilarity: String? = null

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // ----- Document Reader setup -----
        if (FaceSDK.Instance().isInitialized) {
            binding.chbMatchFaces.isChecked = true
            binding.chbMatchFaces.setOnClickListener {
                matchFaces = binding.chbMatchFaces.isChecked
                changeMatchFacesVisibility()
            }
        } else {
            binding.chbMatchFaces.isEnabled = false
            binding.chbMatchFaces.text = "use face matching (unavailable)"
        }

        if (!DocumentReader.Instance().isReady) {
            binding.chbMatchFaces.isEnabled = true
            binding.chbMatchFaces.text = "use face matching"
            binding.showScannerBtn.isEnabled = false
        }

        binding.showScannerBtn.setOnClickListener {
            resetViews()
            val scannerConfig = ScannerConfig.Builder(Scenario.SCENARIO_FULL_AUTH).build()
            DocumentReader.Instance().startScanner(this, scannerConfig) { action, results, error ->
                if (action == DocReaderAction.COMPLETE) {
                    lastDocResults = results
                    showAuthenticityResults(results)
                    if (results?.chipPage != 0 && binding.chbRfid.isChecked) {
                        DocumentReader.Instance().startRFIDReader(this@MainActivity, object: IRfidReaderCompletion() {
                            override fun onCompleted(
                                rfidAction: Int,
                                rfidResults: DocumentReaderResults?,
                                rfidRrror: DocumentReaderException?
                            ) {
                                if (rfidAction == DocReaderAction.COMPLETE || rfidAction == DocReaderAction.CANCEL) {
                                    lastDocResults = rfidResults
                                    showGraphicFieldImage(rfidResults)
                                    if (binding.chbMatchFaces.isChecked) {
                                        faceCapture(rfidResults)
                                    }
                                }
                            }
                        })
                    } else {
                        showGraphicFieldImage(results)
                        if (binding.chbMatchFaces.isChecked) {
                            faceCapture(results)
                        }
                    }
                    Log.d(this@MainActivity.localClassName, "completion raw result: " + results?.rawResult)
                } else {
                    if (action == DocReaderAction.CANCEL) {
                        Toast.makeText(this@MainActivity, "Scanning was cancelled", Toast.LENGTH_LONG).show()
                    } else if (action == DocReaderAction.ERROR) {
                        Toast.makeText(this@MainActivity, "Error:$error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val bleIntent = Intent(this, RegulaBleService::class.java)
        bleIntent.putExtra(
            RegulaBleService.DEVICE_NAME,
            DocumentReader.Instance().functionality().btDeviceName
        )
        bindService(bleIntent, mBleConnection, 0)

        // ----- Fingerprint setup -----
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show()
        }
        setupFingerprint()

        // ----- JSON export button -----
        binding.showJsonBtn.setOnClickListener {
            val json = buildJson()
            val scrollView = android.widget.ScrollView(this)
            val textView = TextView(this)
            textView.text = json
            textView.setTextIsSelectable(true)
            textView.setPadding(32, 24, 32, 24)
            textView.textSize = 11f
            textView.setTypeface(android.graphics.Typeface.MONOSPACE)
            scrollView.addView(textView)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Combined Results JSON")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (mBluetoothAdapter != null && !mBluetoothAdapter!!.isEnabled) {
            if (isPermissionsGranted(this)) {
                val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableIntent, 2)
            }
        } else {
            if (mChatService == null) {
                mChatService = BluetoothReaderService(this, mFingerprintHandler)
            }
        }
    }

    @Synchronized
    public override fun onResume() {
        super.onResume()
        if (isPermissionsGranted(this)) {
            if (mChatService != null) {
                if (mChatService!!.state == Constants.STATE_NONE) {
                    mChatService!!.start()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBleServiceConnected) {
            unbindService(mBleConnection)
            isBleServiceConnected = false
        }
        mChatService?.stop()
        timeOutStop()
    }

    // ===== Fingerprint setup =====
    private fun setupFingerprint() {
        binding.captureFingerBtn.setOnClickListener {
            if (mChatService != null) {
                if (mChatService!!.state != Constants.STATE_CONNECTED) {
                    Toast.makeText(this, "Not connected to scanner", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                imgSize = IMG288
                mUpImageSize = 0
                binding.statusTv.visibility = View.VISIBLE
                binding.statusTv.text = "Place finger on scanner..."
                sendCommand(CMD_GETIMAGE, null, 0)
            }
        }
        binding.fingerprintConnectBtn.setOnClickListener {
            if (checkFingerprintPermissions())
                connectBluetooth()
        }
    }

    private val mFingerprintHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    Constants.STATE_CONNECTED -> Toast.makeText(
                        this@MainActivity,
                        "Scanner connected",
                        Toast.LENGTH_SHORT
                    ).show()
                    Constants.STATE_CONNECTING -> Toast.makeText(
                        this@MainActivity,
                        "Scanner connecting",
                        Toast.LENGTH_SHORT
                    ).show()
                    Constants.STATE_LISTEN, Constants.STATE_NONE -> Toast.makeText(
                        this@MainActivity,
                        "Scanner not connected",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                Constants.MESSAGE_WRITE -> {}
                Constants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    if (readBuf.isNotEmpty()) {
                        if (readBuf[0] == 0x1b.toByte()) {
                            BluetoothReaderHelper.addStatusListHex(readBuf, msg.arg1)
                        } else {
                            receiveCommand(readBuf, msg.arg1)
                        }
                    }
                }
                Constants.MESSAGE_TOAST -> Toast.makeText(
                    applicationContext,
                    msg.data.getString(TOAST),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun timeOutStop() {
        if (mTimerTimeout != null) {
            mTimerTimeout!!.cancel()
            mTimerTimeout = null
            mTaskTimeout!!.cancel()
            mTaskTimeout = null
        }
    }

    private fun timeOutStart() {
        if (mTimerTimeout != null) {
            return
        }
        mTimerTimeout = Timer()
        mHandlerTimeout = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                timeOutStop()
                if (mIsWork) {
                    mIsWork = false
                    binding.statusTv.text = "Timeout - No fingerprint detected"
                    binding.statusTv.postDelayed({ binding.statusTv.visibility = View.GONE }, 3000)
                }
                super.handleMessage(msg)
            }
        }
        mTaskTimeout = object : TimerTask() {
            override fun run() {
                val message = Message()
                message.what = 1
                (mHandlerTimeout as Handler).sendMessage(message)
            }
        }
        mTimerTimeout!!.schedule(mTaskTimeout, 10000, 10000)
    }

    private fun sendCommand(cmdid: Byte, data: ByteArray?, size: Int) {
        if (mIsWork) return
        val sendsize = 9 + size
        val sendbuf = ByteArray(sendsize)
        sendbuf[0] = 'F'.code.toByte()
        sendbuf[1] = 'T'.code.toByte()
        sendbuf[2] = 0
        sendbuf[3] = 0
        sendbuf[4] = cmdid
        sendbuf[5] = size.toByte()
        sendbuf[6] = (size shr 8).toByte()
        if (size > 0) {
            System.arraycopy(data, 0, sendbuf, 7, size)
        }
        val sum = BluetoothReaderHelper.calcCheckSum(sendbuf, 7 + size)
        sendbuf[7 + size] = sum.toByte()
        sendbuf[8 + size] = (sum shr 8).toByte()
        mIsWork = true
        timeOutStart()
        mDeviceCmd = cmdid
        mChatService!!.write(sendbuf)
    }

    private fun receiveCommand(databuf: ByteArray, datasize: Int) {
        if (mDeviceCmd == CMD_GETIMAGE) {
            if (imgSize == IMG288) {
                BluetoothReaderHelper.memcpy(mUpImage, mUpImageSize, databuf, 0, datasize)
                mUpImageSize += datasize
                if (mUpImageSize >= 36864) {
                    timeOutStop()
                    val file = File("/sdcard/test.raw")
                    try {
                        file.createNewFile()
                        val out = FileOutputStream(file)
                        out.write(mUpImage)
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    val imageData = BluetoothReaderHelper.checkHaveTis(mUpImage, mUpImage2, mUpImageSize)
                    val bmpdata: ByteArray = BluetoothReaderHelper.getFingerprintImage(imageData, 256, 288, 0)!!
                    val image = BitmapFactory.decodeByteArray(bmpdata, 0, bmpdata.size)
                    lastFingerprintBitmap = image
                    binding.capturedFingerIv.setImageBitmap(image)
                    mUpImageSize = 0
                    mIsWork = false
                    binding.statusTv.text = "Capture complete"
                    binding.statusTv.postDelayed({ binding.statusTv.visibility = View.GONE }, 2000)
                } else {
                    binding.statusTv.text = "Reading... ${mUpImageSize * 100 / 36864}%"
                }
            }
        }
    }

    private fun connectBluetooth() {
        if (mChatService == null) {
            mChatService = BluetoothReaderService(this, mFingerprintHandler)
        }
        val address = "8C:DE:52:CE:6C:64"
        BluetoothReaderHelper.connectBluetooth(address, mBluetoothAdapter!!, mChatService!!)
    }

    private fun checkFingerprintPermissions(): Boolean {
        if (!this.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            Toast.makeText(this@MainActivity, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return false
        }
        requestBlePermissions(this@MainActivity, PackageManager.PERMISSION_GRANTED)
        return if (isPermissionsGranted(this)) {
            if (!mBluetoothAdapter!!.isEnabled) {
                val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableIntent, 2)
                return false
            }
            true
        } else {
            Toast.makeText(this@MainActivity, "Bluetooth permission is not granted", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ===== Document Reader methods =====

    private fun changeMatchFacesVisibility() {
        when (matchFaces) {
            true -> {
                binding.portraitCameraIv.visibility = View.VISIBLE
                binding.textViewSimilarity.visibility = View.VISIBLE
            }
            false -> {
                binding.portraitCameraIv.visibility = View.GONE
                binding.textViewSimilarity.visibility = View.GONE
            }
        }
    }

    private fun matchFaces(first: Bitmap, second: Bitmap) {
        val firstImage = MatchFacesImage(first, ImageType.LIVE)
        val secondImage = MatchFacesImage(second, ImageType.PRINTED)
        val matchFacesRequest = MatchFacesRequest(arrayListOf(firstImage, secondImage))

        val crop = OutputImageCrop(
            OutputImageCropAspectRatio.OUTPUT_IMAGE_CROP_ASPECT_RATIO_3X4
        )
        val outputImageParams = OutputImageParams(crop, Color.WHITE)
        matchFacesRequest.outputImageParams = outputImageParams

        FaceSDK.Instance().matchFaces(this@MainActivity, matchFacesRequest) { matchFacesResponse: MatchFacesResponse ->
            matchFacesResponse.exception?.let {
                val errorBuilder = "Error: ${matchFacesResponse.exception?.message} Details: ${matchFacesResponse.exception?.detailedErrorMessage}"
                binding.textViewSimilarity.text = errorBuilder
            } ?: run {
                val split = MatchFacesSimilarityThresholdSplit(matchFacesResponse.results, 0.75)
                val similarity = if (split.matchedFaces.size > 0) {
                    split.matchedFaces[0].similarity
                } else if (split.unmatchedFaces.size > 0) {
                    split.unmatchedFaces[0].similarity
                } else {
                    null
                }

                val text = similarity?.let {
                    "Similarity: " + String.format("%.2f", it * 100) + "%"
                } ?: matchFacesResponse.exception?.let {
                    "Similarity: " + it.message
                } ?: "Similarity: "

                lastFaceSimilarity = text
                binding.textViewSimilarity.text = text
            }
        }
    }

    private fun faceCapture(results: DocumentReaderResults?) {
        val configuration = FaceCaptureConfiguration.Builder().setCameraSwitchEnabled(true).build()

        FaceSDK.Instance().presentFaceCaptureActivity(this@MainActivity, configuration) { faceCaptureResponse: FaceCaptureResponse? ->
            if (faceCaptureResponse?.image != null) {
                lastLivePortraitBitmap = faceCaptureResponse.image!!.bitmap
                binding.portraitCameraIv.setImageBitmap(faceCaptureResponse.image!!.bitmap)

                var portrait: Bitmap? = null
                results?.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT, eRPRM_ResultType.RFID_RESULT_TYPE_RFID_IMAGE_DATA)?.let {
                    portrait = it
                }
                if (portrait == null) {
                    results?.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT)?.let {
                        portrait = it
                    }
                }

                if (matchFaces) {
                    portrait?.let {
                        matchFaces(faceCaptureResponse.image!!.bitmap, it)
                    }
                }
            }
        }
    }

    private val mBleConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            isBleServiceConnected = true
            val bleService = (service as RegulaBleService.LocalBinder).service
            bleManager = bleService.bleManager
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBleServiceConnected = false
        }
    }

    private fun resetViews() {
        binding.nameTv.text = ""
        binding.textViewSimilarity.text = ""
        binding.portraitIv.setImageResource(R.drawable.portrait)
        binding.portraitCameraIv.setImageResource(R.drawable.portrait)
        binding.documentImageIv.setImageResource(R.drawable.id)
        binding.uvImageView.setImageResource(R.drawable.id)
        binding.uvImageView.visibility = View.GONE
        binding.irImageView.setImageResource(R.drawable.id)
        binding.irImageView.visibility = View.GONE
        binding.authenticityLayout.visibility = View.GONE
        lastDocResults = null
        lastFingerprintBitmap = null
        lastLivePortraitBitmap = null
        lastFaceSimilarity = null
    }

    private fun showGraphicFieldImage(results: DocumentReaderResults?) {
        results?.getTextFieldValueByType(eVisualFieldType.FT_SURNAME_AND_GIVEN_NAMES).let {
            binding.nameTv.visibility = View.VISIBLE
            binding.nameTv.text = it
        }

        results?.textResult?.fields?.forEach {
            val value = results.getTextFieldValueByType(it.fieldType, it.lcid)
            Log.d("MainActivity", "Text Field: " + it.getFieldName(this@MainActivity) + " value: " + value)
        }

        results?.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT)?.let {
            binding.portraitIv.setImageBitmap(it)
        }

        results?.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT, eRPRM_ResultType.RFID_RESULT_TYPE_RFID_IMAGE_DATA)?.let {
            binding.portraitIv.setImageBitmap(it)
        }

        results?.getGraphicFieldImageByType(eGraphicFieldType.GF_DOCUMENT_IMAGE)?.let {
            val aspectRatio = it.width.toDouble() / it.height.toDouble()
            val documentImage = Bitmap.createScaledBitmap(it, (480 * aspectRatio).toInt(), 480, false)
            binding.documentImageIv.setImageBitmap(documentImage)
        }

        results?.getGraphicFieldByType(eGraphicFieldType.GF_DOCUMENT_IMAGE, eRPRM_ResultType.RPRM_RESULT_TYPE_RAW_IMAGE,
            0, eRPRM_Lights.RPRM_LIGHT_UV)?.let {
            binding.uvImageView.visibility = View.VISIBLE
            binding.uvImageView.setImageBitmap(resizeBitmap(it.bitmap))
        }

        results?.getGraphicFieldByType(eGraphicFieldType.GF_DOCUMENT_IMAGE, eRPRM_ResultType.RPRM_RESULT_TYPE_RAW_IMAGE,
            0, eRPRM_Lights.RPRM_Light_IR_Full)?.let {
            binding.irImageView.visibility = View.VISIBLE
            binding.irImageView.setImageBitmap(resizeBitmap(it.bitmap))
        }
    }

    private fun resizeBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap != null) {
            val aspectRatio = bitmap.width.toDouble() / bitmap.height.toDouble()
            return Bitmap.createScaledBitmap(bitmap, (480 * aspectRatio).toInt(), 480, false)
        }
        return null
    }

    private fun showAuthenticityResults(results: DocumentReaderResults?) {
        results?.authenticityResult?.let {
            binding.authenticityLayout.visibility = View.VISIBLE
            binding.authenticityResultImg.setImageResource(if (results.authenticityResult?.status == eCheckResult.CH_CHECK_OK) R.drawable.correct else R.drawable.incorrect)
            binding.layoutOverallResult.visibility = View.VISIBLE
            binding.overallResultImg.setImageResource(if (results.status.overallStatus == eCheckResult.CH_CHECK_OK) R.drawable.correct else R.drawable.incorrect)
            it.checks.forEach { check ->
                for (element in check.elements) {
                    if (element is DocumentReaderIdentResult) {
                        Log.d(
                            "AuthenticityCheck",
                            "Element status: " + (if (element.status == eCheckResult.CH_CHECK_OK) "Ok" else "Error") + ", percent: " + element.percentValue
                        )
                    } else {
                        Log.d(
                            "AuthenticityCheck",
                            "Element type: " + element.elementType + ", status: " + if (element.status == eCheckResult.CH_CHECK_OK) "Ok" else "Error"
                        )
                    }
                }
            }
        } ?: run {
            binding.authenticityLayout.visibility = View.GONE
        }
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyEdgeToEdgeInsets()
    }

    private fun applyEdgeToEdgeInsets() {
        val rootView = window.decorView.findViewWithTag<View>("content")
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
                )
                insets
            }
        }
    }

    // ===== JSON Export =====

    private fun bitmapToBase64(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildJson(): String {
        val root = JSONObject()
        root.put("timestamp", System.currentTimeMillis())
        root.put("timestampIso", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(Date()))

        // ---- Document results ----
        val doc = JSONObject()
        lastDocResults?.let { results ->
            // Personal info
            val surname = results.getTextFieldValueByType(eVisualFieldType.FT_SURNAME_AND_GIVEN_NAMES)
            doc.put("surnameAndGivenNames", surname ?: JSONObject.NULL)

            // All text fields
            val textFields = JSONArray()
            results.textResult?.fields?.forEach { field ->
                val fieldObj = JSONObject()
                fieldObj.put("name", field.getFieldName(this@MainActivity))
                fieldObj.put("value", results.getTextFieldValueByType(field.fieldType, field.lcid) ?: JSONObject.NULL)
                fieldObj.put("lcid", field.lcid)
                fieldObj.put("fieldType", field.fieldType)
                textFields.put(fieldObj)
            }
            doc.put("textFields", textFields)

            // Authenticity
            results.authenticityResult?.let { auth ->
                val authObj = JSONObject()
                authObj.put("status", if (auth.status == eCheckResult.CH_CHECK_OK) "OK" else "ERROR")
                val checks = JSONArray()
                auth.checks.forEach { check ->
                    check.elements.forEach { element ->
                        val elemObj = JSONObject()
                        if (element is DocumentReaderIdentResult) {
                            elemObj.put("type", "ident")
                            elemObj.put("status", if (element.status == eCheckResult.CH_CHECK_OK) "OK" else "ERROR")
                            elemObj.put("percentValue", element.percentValue)
                        } else {
                            elemObj.put("type", element.elementType.toString())
                            elemObj.put("status", if (element.status == eCheckResult.CH_CHECK_OK) "OK" else "ERROR")
                        }
                        checks.put(elemObj)
                    }
                }
                authObj.put("checks", checks)
                doc.put("authenticity", authObj)
            } ?: doc.put("authenticity", JSONObject.NULL)

            // Overall status
            doc.put("overallStatus", if (results.status.overallStatus == eCheckResult.CH_CHECK_OK) "OK" else "ERROR")

            // Document images
            results.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT, eRPRM_ResultType.RFID_RESULT_TYPE_RFID_IMAGE_DATA)?.let {
                doc.put("portraitRfidBase64", bitmapToBase64(it) ?: JSONObject.NULL)
            } ?: doc.put("portraitRfidBase64", JSONObject.NULL)

            results.getGraphicFieldImageByType(eGraphicFieldType.GF_PORTRAIT)?.let {
                doc.put("portraitBase64", bitmapToBase64(it) ?: JSONObject.NULL)
            } ?: doc.put("portraitBase64", JSONObject.NULL)

            results.getGraphicFieldImageByType(eGraphicFieldType.GF_DOCUMENT_IMAGE)?.let {
                doc.put("documentImageBase64", bitmapToBase64(it) ?: JSONObject.NULL)
            } ?: doc.put("documentImageBase64", JSONObject.NULL)

            results.getGraphicFieldByType(eGraphicFieldType.GF_DOCUMENT_IMAGE, eRPRM_ResultType.RPRM_RESULT_TYPE_RAW_IMAGE,
                0, eRPRM_Lights.RPRM_LIGHT_UV)?.let {
                doc.put("uvImageBase64", bitmapToBase64(it.bitmap) ?: JSONObject.NULL)
            } ?: doc.put("uvImageBase64", JSONObject.NULL)

            results.getGraphicFieldByType(eGraphicFieldType.GF_DOCUMENT_IMAGE, eRPRM_ResultType.RPRM_RESULT_TYPE_RAW_IMAGE,
                0, eRPRM_Lights.RPRM_Light_IR_Full)?.let {
                doc.put("irImageBase64", bitmapToBase64(it.bitmap) ?: JSONObject.NULL)
            } ?: doc.put("irImageBase64", JSONObject.NULL)

        } ?: doc.put("info", "No document scanned yet")

        root.put("document", doc)

        // ---- Fingerprint results ----
        val fp = JSONObject()
        lastFingerprintBitmap?.let {
            fp.put("imageBase64", bitmapToBase64(it) ?: JSONObject.NULL)
            fp.put("width", it.width)
            fp.put("height", it.height)
        } ?: fp.put("info", "No fingerprint captured yet")
        root.put("fingerprint", fp)

        // ---- Face matching ----
        val face = JSONObject()
        lastLivePortraitBitmap?.let {
            face.put("livePortraitBase64", bitmapToBase64(it) ?: JSONObject.NULL)
        } ?: face.put("livePortraitBase64", JSONObject.NULL)

        lastFaceSimilarity?.let {
            face.put("similarity", it)
        } ?: face.put("similarity", JSONObject.NULL)
        root.put("faceMatching", face)

        return root.toString(2)
    }
}
