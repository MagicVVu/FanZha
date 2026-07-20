package com.magicvvu.fanzha.security

interface SecurityDataUploader {
    suspend fun upload(payload: SecurityCollectionPayload)
}
