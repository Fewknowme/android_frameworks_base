/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResultType;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.android.server.biometrics.face.CustomFaceService;

public class Utils {
    private static final String TAG = "BiometricUtils";

    public static boolean isDebugEnabled(Context context, int targetUserId) {
        if (targetUserId == UserHandle.USER_NULL) {
            return false;
        }

        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return false;
        }

        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.BIOMETRIC_DEBUG_ENABLED, 0,
                targetUserId) == 0) {
            return false;
        }
        return true;
    }

    /**
     * Combines {@link BiometricPrompt#KEY_ALLOW_DEVICE_CREDENTIAL} with
     * {@link BiometricPrompt#KEY_AUTHENTICATORS_ALLOWED}, as the former is not flexible enough.
     */
    public static void combineAuthenticatorBundles(Bundle bundle) {
        // Cache and remove explicit ALLOW_DEVICE_CREDENTIAL boolean flag from the bundle.
        final boolean deviceCredentialAllowed =
                bundle.getBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, false);
        bundle.remove(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL);

        final @Authenticators.Types int authenticators;
        if (bundle.containsKey(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED)) {
            // Ignore ALLOW_DEVICE_CREDENTIAL flag if AUTH_TYPES_ALLOWED is defined.
            authenticators = bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, 0);
        } else {
            // Otherwise, use ALLOW_DEVICE_CREDENTIAL flag along with Weak+ biometrics by default.
            authenticators = deviceCredentialAllowed
                    ? Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK
                    : Authenticators.BIOMETRIC_WEAK;
        }

        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
    }

    /**
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return true if device credential is allowed.
     */
    public static boolean isCredentialRequested(@Authenticators.Types int authenticators) {
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }

    /**
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return true if device credential is allowed.
     */
    public static boolean isCredentialRequested(Bundle bundle) {
        return isCredentialRequested(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    public static int getPublicBiometricStrength(@Authenticators.Types int authenticators) {
        // Only biometrics WEAK and above are allowed to integrate with the public APIs.
        return authenticators & Authenticators.BIOMETRIC_WEAK;
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    public static int getPublicBiometricStrength(Bundle bundle) {
        return getPublicBiometricStrength(
                bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
    }

    /**
     * Checks if any of the publicly defined strengths are set.
     *
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return true if biometric authentication is allowed.
     */
    public static boolean isBiometricRequested(Bundle bundle) {
        return getPublicBiometricStrength(bundle) != 0;
    }

    /**
     * @param sensorStrength the strength of the sensor
     * @param requestedStrength the strength that it must meet
     * @return true only if the sensor is at least as strong as the requested strength
     */
    public static boolean isAtLeastStrength(int sensorStrength, int requestedStrength) {
        // Clear out any bits that are not reserved for biometric
        sensorStrength &= Authenticators.BIOMETRIC_MIN_STRENGTH;

        // If the authenticator contains bits outside of the requested strength, it is too weak.
        if ((sensorStrength & ~requestedStrength) != 0) {
            return false;
        }

        for (int i = Authenticators.BIOMETRIC_MAX_STRENGTH;
                i <= requestedStrength; i = (i << 1) | 1) {
            if (i == sensorStrength) {
                return true;
            }
        }

        Slog.e(BiometricService.TAG, "Unknown sensorStrength: " + sensorStrength
                + ", requestedStrength: " + requestedStrength);
        return false;
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param bundle
     * @return
     */
    public static boolean isValidAuthenticatorConfig(Bundle bundle) {
        final int authenticators = bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED);
        return isValidAuthenticatorConfig(authenticators);
    }

    /**
     * Checks if the authenticator configuration is a valid combination of the public APIs
     * @param authenticators
     * @return
     */
    public static boolean isValidAuthenticatorConfig(int authenticators) {
        // The caller is not required to set the authenticators. But if they do, check the below.
        if (authenticators == 0) {
            return true;
        }

        // Check if any of the non-biometric and non-credential bits are set. If so, this is
        // invalid.
        final int testBits = ~(Authenticators.DEVICE_CREDENTIAL
                | Authenticators.BIOMETRIC_MIN_STRENGTH);
        if ((authenticators & testBits) != 0) {
            Slog.e(BiometricService.TAG, "Non-biometric, non-credential bits found."
                    + " Authenticators: " + authenticators);
            return false;
        }

        // Check that biometrics bits are either NONE, WEAK, or STRONG. If NONE, DEVICE_CREDENTIAL
        // should be set.
        final int biometricBits = authenticators & Authenticators.BIOMETRIC_MIN_STRENGTH;
        if (biometricBits == Authenticators.EMPTY_SET
                && isCredentialRequested(authenticators)) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_STRONG) {
            return true;
        } else if (biometricBits == Authenticators.BIOMETRIC_WEAK) {
            return true;
        }

        Slog.e(BiometricService.TAG, "Unsupported biometric flags. Authenticators: "
                + authenticators);
        // Non-supported biometric flags are being used
        return false;
    }

    /**
     * Converts error codes from BiometricConstants, which are used in most of the internal plumbing
     * and eventually returned to {@link BiometricPrompt.AuthenticationCallback} to public
     * {@link BiometricManager} constants, which are used by APIs such as
     * {@link BiometricManager#canAuthenticate(int)}
     *
     * @param biometricConstantsCode see {@link BiometricConstants}
     * @return see {@link BiometricManager}
     */
    public static int biometricConstantsToBiometricManager(int biometricConstantsCode) {
        final int biometricManagerCode;

        switch (biometricConstantsCode) {
            case BiometricConstants.BIOMETRIC_SUCCESS:
                biometricManagerCode = BiometricManager.BIOMETRIC_SUCCESS;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
            case BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED;
                break;
            default:
                Slog.e(BiometricService.TAG, "Unhandled result code: " + biometricConstantsCode);
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
        }
        return biometricManagerCode;
    }

    /**
     * Converts a {@link BiometricPrompt} dismissal reason to an authentication type at the level of
     * granularity supported by {@link BiometricPrompt.AuthenticationResult}.
     *
     * @param reason The reason that the {@link BiometricPrompt} was dismissed. Must be one of:
     *               {@link BiometricPrompt#DISMISSED_REASON_CREDENTIAL_CONFIRMED},
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRMED}, or
     *               {@link BiometricPrompt#DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED}
     * @return An integer representing the authentication type for {@link
     *         BiometricPrompt.AuthenticationResult}.
     * @throws IllegalArgumentException if given an invalid dismissal reason.
     */
    public static @AuthenticationResultType int getAuthenticationTypeForResult(int reason) {
        switch (reason) {
            case BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL;

            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED:
            case BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED:
                return BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC;

            default:
                throw new IllegalArgumentException("Unsupported dismissal reason: " + reason);
        }
    }

    public static boolean isForeground(int callingUid, int callingPid) {
        try {
            final List<ActivityManager.RunningAppProcessInfo> procs =
                    ActivityManager.getService().getRunningAppProcesses();
            if (procs == null) {
                Slog.e(TAG, "No running app processes found, defaulting to true");
                return true;
            }

            for (int i = 0; i < procs.size(); i++) {
                ActivityManager.RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == callingPid && proc.uid == callingUid
                        && proc.importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    public static boolean isKeyguard(Context context, String clientPackage) {
        final boolean hasPermission = context.checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                == PackageManager.PERMISSION_GRANTED;

        final ComponentName keyguardComponent = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.config_keyguardComponent));
        final String keyguardPackage = keyguardComponent != null
                ? keyguardComponent.getPackageName() : null;
        return hasPermission && keyguardPackage != null && keyguardPackage.equals(clientPackage);
    }

    private static boolean containsFlag(int haystack, int needle) {
        return (haystack & needle) != 0;
    }

    public static boolean isUserEncryptedOrLockdown(@NonNull LockPatternUtils lpu, int user) {
        final int strongAuth = lpu.getStrongAuthForUser(user);
        final boolean isEncrypted = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_BOOT);
        final boolean isLockDown = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW)
                || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
        Slog.d(TAG, "isEncrypted: " + isEncrypted + " isLockdown: " + isLockDown);
        return isEncrypted || isLockDown;
    }

    // Check to see if device has power button fingerprint
    public static boolean hasPowerButtonFingerprint(Context context) {
        return context.getResources().getBoolean(com.android.internal.R.bool.config_powerButtonFingerprint)
                   && (Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.FINGERPRINT_POWER_BUTTON_PRESS, 0, UserHandle.USER_CURRENT) == 1);
       }

    public static String[] getConfiguration(Context context) {
        ArrayList<String> sensors = new ArrayList();
        Collections.addAll(sensors, context.getResources().getStringArray(
            R.array.config_biometric_sensors));
        if (CustomFaceService.isSupported()){
            Collections.addAll(sensors, CustomFaceService.getConfiguration());
        }
        return sensors.toArray(new String[0]);
    }
}
