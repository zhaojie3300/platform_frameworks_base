/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.EAPConstants;
import android.net.wifi.FakeKeys;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.CredentialTest}.
 */
@SmallTest
public class CredentialTest {
    private static Credential createCredential(Credential.UserCredential userCred,
                                               Credential.CertificateCredential certCred,
                                               Credential.SimCredential simCred,
                                               X509Certificate caCert,
                                               X509Certificate[] clientCertificateChain,
                                               PrivateKey clientPrivateKey) {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = userCred;
        cred.certCredential = certCred;
        cred.simCredential = simCred;
        cred.caCertificate = caCert;
        cred.clientCertificateChain = clientCertificateChain;
        cred.clientPrivateKey = clientPrivateKey;
        return cred;
    }

    private static Credential createCredentialWithCertificateCredential() {
        Credential.CertificateCredential certCred = new Credential.CertificateCredential();
        certCred.certType = "x509v3";
        certCred.certSha256FingerPrint = new byte[32];
        return createCredential(null, certCred, null, FakeKeys.CA_CERT0,
                new X509Certificate[] {FakeKeys.CLIENT_CERT}, FakeKeys.RSA_KEY1);
    }

    private static Credential createCredentialWithSimCredential() {
        Credential.SimCredential simCred = new Credential.SimCredential();
        simCred.imsi = "1234*";
        simCred.eapType = EAPConstants.EAP_SIM;
        return createCredential(null, null, simCred, null, null, null);
    }

    private static Credential createCredentialWithUserCredential() {
        Credential.UserCredential userCred = new Credential.UserCredential();
        userCred.username = "username";
        userCred.password = "password";
        userCred.eapType = EAPConstants.EAP_TTLS;
        userCred.nonEapInnerMethod = "MS-CHAP";
        return createCredential(userCred, null, null, FakeKeys.CA_CERT0,
                new X509Certificate[] {FakeKeys.CLIENT_CERT}, FakeKeys.RSA_KEY1);
    }

    private static void verifyParcel(Credential writeCred) {
        Parcel parcel = Parcel.obtain();
        writeCred.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        Credential readCred = Credential.CREATOR.createFromParcel(parcel);
        assertTrue(readCred.equals(writeCred));
    }

    /**
     * Verify parcel read/write for a default/empty credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithDefault() throws Exception {
        verifyParcel(new Credential());
    }

    /**
     * Verify parcel read/write for a certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithCertificateCredential() throws Exception {
        verifyParcel(createCredentialWithCertificateCredential());
    }

    /**
     * Verify parcel read/write for a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithSimCredential() throws Exception {
        verifyParcel(createCredentialWithSimCredential());
    }

    /**
     * Verify parcel read/write for an user credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithUserCredential() throws Exception {
        verifyParcel(createCredentialWithUserCredential());
    }

    /**
     * Verify a valid user credential.
     * @throws Exception
     */
    @Test
    public void validateUserCredential() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertTrue(cred.validate());
    }

    /**
     * Verify that an user credential without CA Certificate is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutCaCert() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        assertFalse(cred.validate());
    }

    /**
     * Verify that an user credential with EAP type other than EAP-TTLS is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithEapTls() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertFalse(cred.validate());
    }


    /**
     * Verify that an user credential without realm is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutRealm() throws Exception {
        Credential cred = new Credential();
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertFalse(cred.validate());
    }

    /**
     * Verify that an user credential without username is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutUsername() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertFalse(cred.validate());
    }

    /**
     * Verify that an user credential without password is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutPassword() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertFalse(cred.validate());
    }

    /**
     * Verify that an user credential without auth methoh (non-EAP inner method) is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutAuthMethod() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.caCertificate = FakeKeys.CA_CERT0;
        assertFalse(cred.validate());
    }

    /**
     * Verify a certificate credential. CA Certificate, client certificate chain,
     * and client private key are all required.  Also the digest for client
     * certificate must match the fingerprint specified in the certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredential() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup certificate credential.
        cred.certCredential = new Credential.CertificateCredential();
        cred.certCredential.certType = "x509v3";
        cred.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        // Setup certificates and private key.
        cred.caCertificate = FakeKeys.CA_CERT0;
        cred.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        cred.clientPrivateKey = FakeKeys.RSA_KEY1;
        assertTrue(cred.validate());
    }

    /**
     * Verify that an certificate credential without CA Certificate is invalid.
     *
     * @throws Exception
     */
    public void validateCertCredentialWithoutCaCert() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup certificate credential.
        cred.certCredential = new Credential.CertificateCredential();
        cred.certCredential.certType = "x509v3";
        cred.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        // Setup certificates and private key.
        cred.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        cred.clientPrivateKey = FakeKeys.RSA_KEY1;
        assertFalse(cred.validate());
    }

    /**
     * Verify that a certificate credential without client certificate chain is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithoutClientCertChain() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup certificate credential.
        cred.certCredential = new Credential.CertificateCredential();
        cred.certCredential.certType = "x509v3";
        cred.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        // Setup certificates and private key.
        cred.caCertificate = FakeKeys.CA_CERT0;
        cred.clientPrivateKey = FakeKeys.RSA_KEY1;
        assertFalse(cred.validate());
    }

    /**
     * Verify that a certificate credential without client private key is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithoutClientPrivateKey() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup certificate credential.
        cred.certCredential = new Credential.CertificateCredential();
        cred.certCredential.certType = "x509v3";
        cred.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        // Setup certificates and private key.
        cred.caCertificate = FakeKeys.CA_CERT0;
        cred.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        assertFalse(cred.validate());
    }

    /**
     * Verify that a certificate credential with mismatch client certificate fingerprint
     * is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithMismatchFingerprint() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup certificate credential.
        cred.certCredential = new Credential.CertificateCredential();
        cred.certCredential.certType = "x509v3";
        cred.certCredential.certSha256FingerPrint = new byte[32];
        Arrays.fill(cred.certCredential.certSha256FingerPrint, (byte)0);
        // Setup certificates and private key.
        cred.caCertificate = FakeKeys.CA_CERT0;
        cred.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        cred.clientPrivateKey = FakeKeys.RSA_KEY1;
        assertFalse(cred.validate());
    }

    /**
     * Verify a SIM credential using EAP-SIM.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapSim() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_SIM;
        assertTrue(cred.validate());
    }

    /**
     * Verify a SIM credential using EAP-AKA.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapAka() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_AKA;
        assertTrue(cred.validate());
    }

    /**
     * Verify a SIM credential using EAP-AKA-PRIME.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapAkaPrime() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_AKA_PRIME;
        assertTrue(cred.validate());
    }

    /**
     * Verify that a SIM credential without IMSI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithoutIMSI() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.eapType = EAPConstants.EAP_SIM;
        assertFalse(cred.validate());
    }

    /**
     * Verify that a SIM credential with an invalid IMSI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithInvalidIMSI() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "dummy";
        cred.simCredential.eapType = EAPConstants.EAP_SIM;
        assertFalse(cred.validate());
    }

    /**
     * Verify that a SIM credential with invalid EAP type is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapTls() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_TLS;
        assertFalse(cred.validate());
    }

    /**
     * Verify that a credential contained both an user and a SIM credential is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCredentialWithUserAndSimCredential() throws Exception {
        Credential cred = new Credential();
        cred.realm = "realm";
        // Setup user credential with EAP-TTLS.
        cred.userCredential = new Credential.UserCredential();
        cred.userCredential.username = "username";
        cred.userCredential.password = "password";
        cred.userCredential.eapType = EAPConstants.EAP_TTLS;
        cred.userCredential.nonEapInnerMethod = "MS-CHAP";
        cred.caCertificate = FakeKeys.CA_CERT0;
        // Setup SIM credential.
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_SIM;
        assertFalse(cred.validate());
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithNullSource() throws Exception {
        Credential copyCred = new Credential(null);
        Credential defaultCred = new Credential();
        assertTrue(copyCred.equals(defaultCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with user credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithUserCred() throws Exception {
        Credential sourceCred = createCredentialWithUserCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithCertCred() throws Exception {
        Credential sourceCred = createCredentialWithCertificateCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithSimCred() throws Exception {
        Credential sourceCred = createCredentialWithSimCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }
}