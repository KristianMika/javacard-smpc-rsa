package tests;

import applet.RSAClientSign;

import cardTools.CardManager;
import cardTools.RunConfig;
import cardTools.Util;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Test class.
 * Note: If simulator cannot be started try adding "-noverify" JVM parameter
 *
 * @author Petr Svenda, Dusan Klinec (ph4r05), Lukas Zaoral
 */
public class SimpleAPDU {
    public static final byte CLA_RSA_SMPC_CLIENT_SIGN = 0x00;

    public static final byte INS_SET_KEYS = 0x10;
    public static final byte INS_SET_MESSAGE = 0x12;
    public static final byte INS_RESET = 0x14;
    public static final byte INS_SIGNATURE = 0x16;

    public static final byte P1_SET_D = 0x00;
    public static final byte P1_SET_N = 0x01;

    public static final byte SINGLE_PART = 0x00;
    public static final byte MULTI_PART = 0x10;

    public static final byte PART_0 = 0x00;
    public static final byte PART_1 = 0x01;

    public static final byte NONE = 0x00;

    public static final String TEST_PATH = "src/test/java/tests/";
    public static final String CLIENT_KEY_CLIENT_SHARE_FILE = TEST_PATH + "client_card.key";
    public static final String MESSAGE_FILE = TEST_PATH + "message.txt";
    public static final String CLIENT_SHARE_SIG_FILE = TEST_PATH + "client.sig";

    public static final short CLIENT_ARR_LEN = 256;

    private final BigInteger n;
    private static final short MAX_APDU_LENGTH = 0xFF;

    private static String APPLET_AID = "0102030405060708090102";
    private static byte[] APPLET_AID_BYTE = Util.hexStringToByteArray(APPLET_AID);

    private final ArrayList<CommandAPDU> APDU_SET_N = new ArrayList<>();
    private final ArrayList<CommandAPDU> APDU_SET_D = new ArrayList<>();
    private final ArrayList<CommandAPDU> APDU_MESSAGE = new ArrayList<>();

    private static final CardManager cardMgr = new CardManager(APPLET_AID_BYTE);

    /**
     *
     * @param realCard
     * @throws Exception
     */
    public SimpleAPDU(boolean realCard) throws Exception {
        try (InputStream in = new FileInputStream(CLIENT_KEY_CLIENT_SHARE_FILE)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            byte[] num = Util.hexStringToByteArray(reader.readLine());
            BigInteger d = new BigInteger(1, num);

            setNumber(APDU_SET_D, num, INS_SET_KEYS, P1_SET_D);

            num = Util.hexStringToByteArray(reader.readLine());
            n = new BigInteger(1, num);

            if (num.length != CLIENT_ARR_LEN)
                throw new IllegalArgumentException("Modulus is not a 256-bit number.");

            if (d.compareTo(n) > 0)
                throw new IllegalArgumentException("Private key cannot be larger than modulus.");

            setNumber(APDU_SET_N, num, INS_SET_KEYS, P1_SET_N);

            if (reader.readLine() != null)
                throw new IOException(String.format("Wrong '%s' file format.", CLIENT_KEY_CLIENT_SHARE_FILE));
        }

        final RunConfig runCfg = RunConfig.getDefaultConfig();

        if (realCard)
            runCfg.setTestCardType(RunConfig.CARD_TYPE.PHYSICAL);
        else {
            runCfg.setAppletToSimulate(RSAClientSign.class)
                    .setTestCardType(RunConfig.CARD_TYPE.JCARDSIMLOCAL)
                    .setbReuploadApplet(true)
                    .setInstallData(new byte[8]);
        }

        System.out.print("Connecting to card...");
        if (!cardMgr.Connect(runCfg)) {
            System.out.println(" Fail.");
            return;
        }
        System.out.println(" Done.");
    }

    /**
     *
     *
     * @param cmds
     * @param num
     * @param ins
     * @param p1
     */
    private void setNumber(ArrayList<CommandAPDU> cmds, byte[] num, byte ins, byte p1) {
        if (num.length <= MAX_APDU_LENGTH) {
            cmds.add(new CommandAPDU(CLA_RSA_SMPC_CLIENT_SIGN, ins, p1, PART_0 | SINGLE_PART, num));
            return;
        }

        for (int i = num.length; i > 0; i -= MAX_APDU_LENGTH) {
            cmds.add(new CommandAPDU(
                    CLA_RSA_SMPC_CLIENT_SIGN, ins, p1 , (i / MAX_APDU_LENGTH > 0 ? PART_0 : PART_1) | MULTI_PART,
                    Arrays.copyOfRange(num, i - MAX_APDU_LENGTH > 0 ? i - MAX_APDU_LENGTH : 0, i)
            ));
        }
    }


    /**
     *
     * @param cmd
     * @return
     * @throws Exception
     */
    public ResponseAPDU transmit(CommandAPDU cmd) throws Exception {
        return cardMgr.transmit(cmd);
    }

    /**
     *
     * @param cmd
     * @throws Exception
     */
    public void transmitNumber(ArrayList<CommandAPDU> cmd) throws Exception {
        for (CommandAPDU c : cmd) {
            ResponseAPDU res = transmit(c);
            if (res.getSW() != 0x9000)
                throw new CardException(String.format("Expected card response: %d", res.getSW()));
        }
    }

    /**
     *
     * @throws Exception
     */
    public void setKeys() throws Exception {
        transmitNumber(APDU_SET_D);
        transmitNumber(APDU_SET_N);
    }

    /**
     *
     * @return
     * @throws Exception
     */
    public ResponseAPDU signMessage() throws Exception {
        String message;

        try (InputStream in = new FileInputStream(MESSAGE_FILE)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            message = reader.readLine();
            byte[] num = Util.hexStringToByteArray(message);

            BigInteger m = new BigInteger(num);
            if (m.compareTo(n) > 0)
                throw new IllegalArgumentException("Message key cannot be larger than modulus.");

            setNumber(APDU_MESSAGE, num, INS_SET_MESSAGE, NONE);

            if (reader.readLine() != null)
                throw new IOException(String.format("Wrong '%s' file format.", MESSAGE_FILE));
        }

        transmitNumber(APDU_MESSAGE);
        ResponseAPDU response = cardMgr.transmit(
            new CommandAPDU(CLA_RSA_SMPC_CLIENT_SIGN, INS_SIGNATURE, NONE, NONE, CLIENT_ARR_LEN)
        );

        try (OutputStream out = new FileOutputStream(CLIENT_SHARE_SIG_FILE)) {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out));
            writer.write(String.format(
                    "%s%n%s%n", message, Util.toHex(Util.trimLeadingZeroes(response.getData()))
            ));
            writer.flush();
        }

        return response;
    }

}
