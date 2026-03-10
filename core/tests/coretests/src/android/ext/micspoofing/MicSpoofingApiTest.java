package android.ext.micspoofing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MicSpoofingApiTest {

    @Test
    public void customPathConfigRoundTrip() {
        var path = "/storage/emulated/0/Music/test.wav";

        var config = MicSpoofingApi.buildCustomPathConfig(path);

        assertEquals(MicSpoofingApi.MODE_CUSTOM_PATH, MicSpoofingApi.getSourceMode(config));
        assertEquals(path, MicSpoofingApi.getPath(config));
    }

    @Test
    public void defaultConfigHasNoPath() {
        var config = MicSpoofingApi.buildDefaultConfig();

        assertNull(MicSpoofingApi.getPath(config));
        assertEquals(MicSpoofingApi.MODE_DEFAULT, MicSpoofingApi.getSourceMode(config));
    }

    @Test
    public void getPathReturnsNullForNull() {
        assertNull(MicSpoofingApi.getPath(null));
    }

    @Test
    public void getPathReturnsNullForTruncatedConfig() {
        assertNull(MicSpoofingApi.getPath(new byte[]{MicSpoofingApi.VERSION}));
    }

}
