package ru.injent.service.google

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes.DRIVE
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.io.File

val googleModule = module {
    single<JsonFactory> {
        GsonFactory.getDefaultInstance()
    }
    single<HttpTransport> {
        GoogleNetHttpTransport.newTrustedTransport()
    }
    single<HttpRequestInitializer> {
        getGoogleCredential(
            credentialFile = File(System.getenv("GOOGLE_CREDENTIALS_PATH") ?: "google/credentials.json"),
            tokensDirectory = File(System.getenv("GOOGLE_TOKENS_DIR") ?: "tokens"),
            httpTransport = get(),
            jsonFactory = get()
        )
    }
    single {
        Sheets.Builder(get(), get(), get())
            .setApplicationName(APP_NAME)
            .build()
    }
    single {
        Drive.Builder(get(), get(), get())
            .setApplicationName(APP_NAME)
            .build()
    }
    singleOf(::NewGoogleService)
}

private const val APP_NAME = "Validation Sheets"

private fun getGoogleCredential(
    credentialFile: File,
    tokensDirectory: File,
    httpTransport: HttpTransport,
    jsonFactory: JsonFactory,
): Credential {
    val clientSecrets = GoogleClientSecrets.load(
        jsonFactory, credentialFile.inputStream().bufferedReader()
    )
    val flow = GoogleAuthorizationCodeFlow.Builder(
        httpTransport, jsonFactory, clientSecrets, listOf(
            SPREADSHEETS,
            DRIVE,
        )
    )
        .setDataStoreFactory(FileDataStoreFactory(tokensDirectory))
        .setAccessType("offline")
        .build()

    val receiver = LocalServerReceiver.Builder().setPort(8888).build()
    val credential = AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    return credential
}
