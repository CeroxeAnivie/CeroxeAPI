package top.ceroxe.api.security.encryption;

import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SecureSocketCompatibilityTest {

    @Test
    public void x25519RuntimeRequirementMatchesLibraryContract() {
        assertTrue("ceroxe-core-android requires API 33+ because SecureSocket uses X25519/XDH", Build.VERSION.SDK_INT >= 33);
    }
}
