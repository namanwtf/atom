package atom.context

import atom.context.sources.Otp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtpTest {
    @Test
    fun plainCode() = assertEquals("482913", Otp.extract("Your verification code is 482913. Do not share it."))

    @Test
    fun googleStylePrefix() = assertEquals("123456", Otp.extract("G-123456 is your Google verification code."))

    @Test
    fun codeBeforeKeyword() = assertEquals("7731", Otp.extract("7731 is your OTP for login"))

    @Test
    fun splitCode() = assertEquals("123456", Otp.extract("Use code 123-456 to sign in"))

    @Test
    fun skipsAmountAndPicksCode() =
        assertEquals("552190", Otp.extract("OTP 552190 for txn of Rs. 2500.00 at AMAZON on card XX4321. Valid 10 mins."))

    @Test
    fun skipsAccountEndingDigits() =
        assertEquals("908172", Otp.extract("Acct XX4821 debited. To confirm use one-time password 908172"))

    @Test
    fun skipsYearInDate() = assertEquals("3381", Otp.extract("Your login code: 3381 (sent 12/03/2026)"))

    @Test
    fun noKeywordNoCode() = assertNull(Otp.extract("Meet at 1930 near gate 4521"))

    @Test
    fun noDigitsNoCode() = assertNull(Otp.extract("Your verification code will arrive shortly"))

    @Test
    fun longNumbersAreNotCodes() = assertNull(Otp.extract("Call 18001234567 to verify your account"))

    @Test
    fun chineseKeyword() = assertEquals("839201", Otp.extract("【某银行】验证码839201，5分钟内有效。"))

    @Test
    fun percentIsNotCode() = assertNull(Otp.extract("Security alert: 2500% bonus code inside"))
}
