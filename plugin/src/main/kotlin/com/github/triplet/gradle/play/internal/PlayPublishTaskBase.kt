package com.github.triplet.gradle.play.internal

import com.android.build.gradle.api.ApplicationVariant
import com.github.triplet.gradle.play.PlayPublisherExtension
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory

abstract class PlayPublishTaskBase : DefaultTask() {
    @get:Nested lateinit var extension: PlayPublisherExtension
    @get:Internal lateinit var variant: ApplicationVariant
    @get:Nested lateinit var accountConfig: AccountConfig

    @get:Internal
    protected val applicationId: String by lazy { extension.appId ?: variant.applicationId }
    @get:Internal
    protected val progressLogger: ProgressLogger = services[ProgressLoggerFactory::class.java]
            .newOperation(javaClass)

    private val publisher by lazy {
        val credential = accountConfig.run {
            val jsonFile = jsonFile
            val pk12File = pk12File
            val serviceAccountEmail = serviceAccountEmail
            val factory = JacksonFactory.getDefaultInstance()

            if (jsonFile != null) {
                GoogleCredential.fromStream(jsonFile.inputStream(), transport, factory)
                        .createScoped(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
            } else if (pk12File != null && serviceAccountEmail != null) {
                GoogleCredential.Builder()
                        .setTransport(transport)
                        .setJsonFactory(factory)
                        .setServiceAccountId(serviceAccountEmail)
                        .setServiceAccountPrivateKeyFromP12File(pk12File)
                        .setServiceAccountScopes(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
                        .build()
            } else {
                throw IllegalArgumentException("No credentials provided.")
            }
        }

        AndroidPublisher.Builder(transport, JacksonFactory.getDefaultInstance()) {
            credential.initialize(it.apply {
                readTimeout = 100_000
                connectTimeout = 100_000
            })
        }.setApplicationName(PLUGIN_NAME).build()
    }

    protected fun read(block: AndroidPublisher.Edits.(editId: String) -> Unit) {
        val edits = publisher.edits()
        val request = edits.insert(applicationId, null)

        val id = try {
            request.execute().id
        } catch (e: GoogleJsonResponseException) {
            // Rethrow for clarity
            if (e.details.errors.any { it.reason == "applicationNotFound" }) {
                throw IllegalArgumentException(
                        "No application found for the package name $applicationId. " +
                                "The first version of your app must be uploaded via the " +
                                "Play Store console.", e)
            } else if (e.statusCode == 401) {
                throw IllegalArgumentException("Invalid service account credentials.", e)
            } else {
                throw e
            }
        }

        edits.block(id)
    }

    protected inline fun write(
            crossinline block: AndroidPublisher.Edits.(editId: String) -> Unit
    ) = read {
        block(it)
        commit(applicationId, it).execute()
    }
}
