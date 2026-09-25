/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a [PlaybackException] into something a person can act on.
 *
 * "ERROR_CODE_IO_BAD_HTTP_STATUS" tells a user nothing. Worse, the habit of showing a raw
 * error code on screen is what led one player to *remove* its 403 display entirely as a
 * "fix" — hiding the symptom while the stream still failed. The right answer is to say what
 * probably went wrong and what to try.
 */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    fun describe(error: PlaybackException): String {
        (error.cause as? HttpDataSource.InvalidResponseCodeException)?.let {
            return describeHttpStatus(it.responseCode)
        }
        if (error.cause is UnknownHostException) {
            return "Serveur inaccessible. Vérifiez la connexion et l’adresse."
        }
        if (error.cause is SocketTimeoutException) {
            return "Le serveur ne répond plus. Il est peut-être surchargé — réessayez dans un instant."
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Connexion au serveur perdue."

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "Le serveur a renvoyé une page web au lieu d’un flux. Le lien a peut-être expiré."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "Ce flux est invalide et ne peut pas être lu."

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "Cet appareil ne possède aucun décodeur pour ce format."

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Cet appareil ne peut pas décoder ce format vidéo. Une autre qualité peut fonctionner."

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "La lecture est trop éloignée du direct. Rechargement."

            else -> "Échec de la lecture (${error.errorCodeName})."
        }
    }

    fun describeHttpStatus(code: Int): String = when (code) {
        401 -> "Le serveur a refusé ces identifiants. Vérifiez le nom d’utilisateur et le mot de passe."
        403 -> "Le serveur a refusé ce flux (403). Le compte diffuse peut-être déjà sur un autre " +
            "appareil, ou le fournisseur bloque l’agent utilisateur de l’application. Modifiez-le " +
            "dans les réglages avancés de la source."
        404 -> "Cette chaîne n’existe plus sur le serveur. Actualisez la liste des chaînes."
        405 -> "Le serveur a refusé la requête (405). L’adresse du flux semble incorrecte pour ce " +
            "fournisseur — essayez le mode Xtream ou liste de lecture."
        429 -> "Trop de requêtes. Le fournisseur limite temporairement cet appareil."
        451 -> "Contenu bloqué pour des raisons légales dans cette région."
        in 500..599 -> "Le serveur du fournisseur rencontre un problème ($code)."
        else -> "Le serveur a renvoyé le code HTTP $code."
    }
}
