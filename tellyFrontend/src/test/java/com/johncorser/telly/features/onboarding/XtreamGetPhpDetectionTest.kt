package com.johncorser.telly.features.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamGetPhpDetectionTest {
    @Test
    fun detectsXtreamCredentialsFromGetPhpUrl() {
        val detected =
            detectXtreamGetPhp(
                "http://king5king.com:8080/get.php?username=test%40user&password=p%40ss&type=m3u_plus&output=ts",
            )

        assertThat(detected).isNotNull()
        assertThat(detected!!.baseUrl).isEqualTo("http://king5king.com:8080")
        assertThat(detected.username).isEqualTo("test@user")
        assertThat(detected.password).isEqualTo("p@ss")
    }

    @Test
    fun leavesNormalM3uUrlAlone() {
        val detected = detectXtreamGetPhp("https://example.com/channels/list.m3u8")
        assertThat(detected).isNull()
    }
}
