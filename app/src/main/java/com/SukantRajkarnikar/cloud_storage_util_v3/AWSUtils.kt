package com.sukantrajkarnikar.cloud_storage_util_v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.text.TextUtils
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.*
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.amazonaws.services.s3.model.ListObjectsV2Result
import com.sukantrajkarnikar.cloud_storage_util_v3.errors.ImageCorruptedException
import java.io.*
import java.lang.ref.WeakReference

class AWSUtils private constructor(context: Context?) {
    private var weakContext: WeakReference<Context>? = null

    init {
        this.weakContext = WeakReference(context)
    }

    private var observer: TransferObserver? = null
    private var imageFile: File? = null
    private var mTransferUtility: TransferUtility? = null
    private var sS3Client: AmazonS3Client? = null
    private var sCredProvider: CognitoCachingCredentialsProvider? = null
    private lateinit var awsMetaInfo: AwsMetaInfo
    private var retryCount = 0
    private var onAwsImageUploadListener: OnAwsImageUploadListener? = null

    companion object {
        private const val MAX_RETRY_COUNT: Int = 2
        private var awsUtils: AWSUtils? = null

        @Synchronized
        internal fun getInstance(context: Context?): AWSUtils? {
            if (null == awsUtils) {
                awsUtils = AWSUtils(context)
            }
            return awsUtils
        }

        fun get(): AWSUtils? {
            return awsUtils
        }
    }

    private fun getContext(): Context? {
        return this.weakContext?.get()
    }

    @Deprecated("replaced by kotlin-lamda function.")
    fun setListener(onAwsImageUploadListener: OnAwsImageUploadListener) {
        this.onAwsImageUploadListener = onAwsImageUploadListener
    }

    @Deprecated("replaced by kotlin-lamda function.")
    fun removeListener() {
        this.onAwsImageUploadListener = null
    }

    private fun getCredProvider(context: Context): CognitoCachingCredentialsProvider? {
        if (sCredProvider == null) {
            sCredProvider = CognitoCachingCredentialsProvider(
                context.applicationContext,
                awsMetaInfo.serviceConfig.cognitoPoolId,
                getRegions(awsMetaInfo)
            )
        }
        return sCredProvider
    }

    private fun getS3Client(context: Context?): AmazonS3Client? {
        val timeoutConnection = 60000

        val clientConfiguration = ClientConfiguration().apply {
            maxErrorRetry = 3
            connectionTimeout = timeoutConnection
            socketTimeout = timeoutConnection
        }

        if (sS3Client == null) {
            sS3Client = AmazonS3Client(
                getCredProvider(context!!),
                Region.getRegion(awsMetaInfo.serviceConfig.region),
                clientConfiguration
            )
        }
        return sS3Client
    }

    private fun getTransferUtility(context: Context): TransferUtility? {
        if (mTransferUtility == null) {
            val tuOptions = TransferUtilityOptions()
            tuOptions.transferThreadPoolSize = 10
            // 10 threads for upload and download operations.

            // Initializes TransferUtility
            mTransferUtility =
                TransferUtility.builder().s3Client(getS3Client(context.applicationContext))
                    .context(context.applicationContext).transferUtilityOptions(tuOptions).build()
        }
        return mTransferUtility
    }

    fun beginUpload(
        awsMetaInfo: AwsMetaInfo,
        showProgress: () -> Unit,
        onSuccess: (String, AwsMetaInfo) -> Unit,
        onError: (Throwable, AwsMetaInfo) -> Unit,
        onStateChanged: (String) -> Unit,
        onProgressChanged: (Int, byteCurrent: Float, byteTotal: Float) -> Unit,
    ) {
        this.awsMetaInfo = awsMetaInfo
        if (TextUtils.isEmpty(awsMetaInfo.imageMetaInfo.imagePath)) {
            onError(
                FileNotFoundException("Could not find the filepath of the selected file"),
                awsMetaInfo
            )
            /* onAwsImageUploadListener?.onError(
                 FileNotFoundException("Could not find the filepath of the selected file"),
                 awsMetaInfo
             )*/
            return
        }

        val oldExif = ExifInterface(awsMetaInfo.imageMetaInfo.imagePath)
        val compressedImage = compressAwsImage(awsMetaInfo)
        val compressedImagePath = compressedImage.first
        val compressedBitmap = compressedImage.second
        val newExifOrientation = setImageOrientation(oldExif, compressedImagePath)
        if (newExifOrientation == null) {
            onError(
                ImageCorruptedException("Cannot change orientation of image. Image may be corrupted."),
                awsMetaInfo
            )
            /* onAwsImageUploadListener?.onError(
                 ImageCorruptedException("Cannot change orientation of image. Image may be corrupted."),
                 awsMetaInfo
             )*/
            return
        }
        try {
            val rotation = getRotation(newExifOrientation)
            if (rotation != null) {
                val matrix = Matrix()
                matrix.postRotate(rotation)
                setPostScale(newExifOrientation, matrix)
                if (compressedBitmap != null) {
                    val rotatedBitmap = Bitmap.createBitmap(
                        compressedBitmap,
                        0,
                        0,
                        compressedBitmap.width,
                        compressedBitmap.height,
                        matrix,
                        true
                    )
                    if (rotatedBitmap != null) {
                        // rotatedBitmap will be recycled inside addAwsWaterMark function
                        val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, rotatedBitmap)
                        waterMarkBitmap.recycle()
                    }
                    compressedBitmap.recycle()
                }
            } else {
                if (compressedBitmap != null) {
                    val newBitmap = Bitmap.createBitmap(
                        compressedBitmap, 0, 0, compressedBitmap.width, compressedBitmap.height
                    )
                    if (newBitmap != null) {
                        // newBitmap will be recycled inside addAwsWaterMark function
                        val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, newBitmap)
                        waterMarkBitmap.recycle()
                    }
                    compressedBitmap.recycle()
                }
            }
        } catch (error: Exception) {
            error.printStackTrace()
            awsMetaInfo.imageMetaInfo.imagePath = compressedImagePath
        }
        /*  if (compressedBitmap != null) {
              val newBitmap = Bitmap.createBitmap(
                  compressedBitmap,
                  0,
                  0,
                  compressedBitmap.width,
                  compressedBitmap.height
              )
              if (newBitmap != null) {
                  // newBitmap will be recycled inside addAwsWaterMark function
                  val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, newBitmap)
                  waterMarkBitmap.recycle()
              }
              compressedBitmap.recycle()
          }*/

        val file = File(awsMetaInfo.imageMetaInfo.imagePath)
        imageFile = file
//        onAwsImageUploadListener?.showProgress()
        showProgress()

        observer = getContext()?.let {
            getTransferUtility(it)?.upload(
                awsMetaInfo.serviceConfig.bucketName, //Bucket name
                "${awsMetaInfo.awsFolderPath}/${imageFile?.name}", imageFile
            )
        }
        observer?.setTransferListener(
            UploadListener(
                onSuccess, onError, onStateChanged, onProgressChanged
            )
        )
    }

    private inner class UploadListener(
        private val onSuccess: (String, AwsMetaInfo) -> Unit,
        private val onError: (Throwable, AwsMetaInfo) -> Unit,
        private val onStateChanged: (String) -> Unit,
        private val onProgressChanged: (Int, byteCurrent: Float, byteTotal: Float) -> Unit,
    ) : TransferListener {
        override fun onError(id: Int, e: Exception) {
//            onAwsImageUploadListener?.onError(e, awsMetaInfo)
            onError(e, awsMetaInfo)
        }

        override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
            onProgressChanged(id, bytesCurrent.toFloat(), bytesTotal.toFloat())
            /* onAwsImageUploadListener?.onProgressChanged(
                 id,
                 bytesCurrent.toFloat(),
                 bytesTotal.toFloat()
             )*/
        }

        override fun onStateChanged(id: Int, newState: TransferState) {
            if (newState == TransferState.COMPLETED) {
                val finalImageUrl =
                    "${awsMetaInfo.serviceConfig.url}${awsMetaInfo.awsFolderPath}/${imageFile?.name}"
//                onAwsImageUploadListener?.onSuccess(finalImageUrl)
                onSuccess(finalImageUrl, awsMetaInfo)
            } else if (newState == TransferState.CANCELED) {
//                onAwsImageUploadListener?.onStateChanged(getState(newState))
                onStateChanged(getState(newState))
            } else if (newState == TransferState.FAILED) {
                if (retryCount != MAX_RETRY_COUNT) {
                    retryCount += 1
                    observer = mTransferUtility?.resume(id)
                    observer?.setTransferListener(this)
                } else {
//                    onAwsImageUploadListener?.onStateChanged(getState(newState))
                    onStateChanged(getState(newState))
                }
            }
        }
    }

    private fun compressAwsImage(awsMetaInfo: AwsMetaInfo): Pair<String, Bitmap?> {
        return try {
            val byteArray = streamToByteArray(FileInputStream(awsMetaInfo.imageMetaInfo.imagePath))
            val bitmap = decodeSampledBitmapFromResource(
                byteArray,
                awsMetaInfo.imageMetaInfo.imageWidth ?: AwsConstant.DEFAULT_IMAGE_WIDTH,
                awsMetaInfo.imageMetaInfo.imageHeight ?: AwsConstant.DEFAULT_IMAGE_HEIGHT,
                awsMetaInfo.imageMetaInfo.waterMarkInfo
            )

            val stream = ByteArrayOutputStream()
            bitmap.compress(
                awsMetaInfo.imageMetaInfo.compressFormat,
                awsMetaInfo.imageMetaInfo.compressLevel,
                stream
            )
            val os: OutputStream = FileOutputStream(awsMetaInfo.imageMetaInfo.imagePath)
            os.write(stream.toByteArray())
            os.close()
            Pair(awsMetaInfo.imageMetaInfo.imagePath, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(awsMetaInfo.imageMetaInfo.imagePath, null)
        }
    }

    private fun getRegions(awsMetaInfo: AwsMetaInfo): Regions {
        return when (awsMetaInfo.serviceConfig.region) {
            "ap-southeast-1" -> Regions.AP_SOUTHEAST_1
            "ap-south-1" -> Regions.AP_SOUTH_1
            "ap-east-1" -> Regions.AP_EAST_1
            else -> throw IllegalArgumentException("Invalid region : add other region if required (Cloud storage util library)")
        }
    }

    private fun getRegion(region: String): Regions {
        return when (region) {
            "ap-southeast-1" -> Regions.AP_SOUTHEAST_1
            "ap-south-1" -> Regions.AP_SOUTH_1
            "ap-east-1" -> Regions.AP_EAST_1
            else -> throw IllegalArgumentException("Invalid region : add other region if required (Cloud storage util library)")
        }
    }

    private fun getState(newState: TransferState): String {
        return when (newState) {
            TransferState.CANCELED -> AWSTransferState.STATE_CANCELED
            TransferState.COMPLETED -> AWSTransferState.STATE_COMPLETED
            TransferState.FAILED -> AWSTransferState.STATE_FAILED
            else -> AWSTransferState.STATE_UNKNOWN
        }
    }

    private fun getRotation(exifOrientation: Int): Float? {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            else -> null
        }
    }

    private fun setPostScale(exifOrientation: Int, matrix: Matrix) {
        when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.postScale(-1f, 1f)
        }
    }

    private fun setImageOrientation(oldExif: ExifInterface, newImagePath: String): Int? {
        try {
            val exifOrientation = oldExif.getAttribute(ExifInterface.TAG_ORIENTATION)
            if (exifOrientation != null) {
                val newExif = ExifInterface(newImagePath)
                newExif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation)
                newExif.saveAttributes()
                return newExif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
                )
            }

        } catch (error: Exception) {
            error.printStackTrace()
            return null
        }
        return null
    }

    fun listAllTheObjects(
        bucket: String,
        region: String,
        poolId: String,
        folderPath: String,
        onSuccess: (List<S3ObjectSummary>) -> Unit
    ) {
        val req = ListObjectsV2Request().withBucketName(bucket).withPrefix(folderPath)
//            .withDelimiter("")
        val timeoutConnection = 60000
        val clientConfiguration = ClientConfiguration().apply {
            maxErrorRetry = 3
            connectionTimeout = timeoutConnection
            socketTimeout = timeoutConnection
        }

        if (sCredProvider == null) {
            sCredProvider = CognitoCachingCredentialsProvider(
                getContext()?.applicationContext, poolId, getRegion(region)
            )
        }

        if (sS3Client == null) {
            sS3Client = AmazonS3Client(
                getContext()?.let { getCredProvider(it) },
                Region.getRegion(region),
                clientConfiguration
            )
        }

        Thread {
            try {
                val listing: ListObjectsV2Result = sS3Client!!.listObjectsV2(req)
                for (commonPrefix in listing.commonPrefixes) {
                    println("Common prefix--->$commonPrefix")
                }
                val objectList = mutableListOf<S3ObjectSummary>()
                for (summary in listing.objectSummaries) {
                    val baseUrl = "https://$bucket.s3.$region.amazonaws.com"
                    objectList.add(
                        S3ObjectSummary(
                            key = summary.key,
                            eTag = summary.eTag,
                            lastModified = summary.lastModified,
                            storageClass = summary.storageClass,
                            owner = summary.owner?.displayName,
                            ownerId = summary.owner?.id,
                            size = summary.size,
                            baseUrl = baseUrl
                        )
                    )
                }
                onSuccess(objectList)
            } catch (error: Exception) {
                error.printStackTrace()
                onSuccess(listOf())
            }

        }.start()
    }

    @Deprecated("This listener won't work from version (2.2.2), replaced by kotlin-lamda function.")
    interface OnAwsImageUploadListener {
        fun showProgress()
        fun onProgressChanged(id: Int, currentByte: Float, totalByte: Float)
        fun onSuccess(imgUrl: String)
        fun onError(error: Throwable, awsMetaInfo: AwsMetaInfo)
        fun onStateChanged(state: String)
    }
}