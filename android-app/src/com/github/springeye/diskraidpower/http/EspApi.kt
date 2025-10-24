package com.github.springeye.diskraidpower.http

import de.jensklingenberg.ktorfit.http.GET

interface EspApi {
    @GET("people/1/")
    suspend fun getPerson(): String
}