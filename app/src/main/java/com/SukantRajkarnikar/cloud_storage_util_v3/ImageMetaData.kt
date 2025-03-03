package com.sukantrajkarnikar.cloud_storage_util_v3

interface ImageMetaData {
    fun getLat(): Double
    fun getLng(): Double
    fun getCloudUrl(): String
    fun getTimeStamp(): String
    fun getPathOfImage(): String
    fun getOutletName(): String
    fun getImageId(): Int
    fun getImageType(): String
    fun getUsersName(): String
    fun getBuName(): String
}