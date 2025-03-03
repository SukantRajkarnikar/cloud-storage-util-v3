package com.sukantrajkarnikar.cloud_storage_util_v3

import java.util.*

class S3ObjectSummary(
    var key: String?,
    var eTag: String?,
    var lastModified: Date?,
    var storageClass: String?,
    var owner: String?,
    var ownerId: String?,
    var size: Long?,
    var baseUrl: String?
)