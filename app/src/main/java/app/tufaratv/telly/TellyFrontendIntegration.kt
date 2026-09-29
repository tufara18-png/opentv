/*
 * Telly frontend integration entry point.
 *
 * Telly source is vendored as a git submodule and compiled as the :tellyFrontend
 * Android library. OpenTV keeps ownership of catalog/source normalization and will
 * progressively replace Telly repository implementations with OpenTV-backed adapters.
 */
package app.tufaratv.telly

object TellyFrontendIntegration {
    const val UPSTREAM_REPOSITORY = "johnpc/telly"
    const val UPSTREAM_COMMIT = "00a2b64fa23061d296324d8fee8e456aa20b4b96"
}
