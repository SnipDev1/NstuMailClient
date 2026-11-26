package ru.nstu.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.EwsXml
import ru.nstu.mail.data.MailFolderType
import java.util.Base64

class EwsXmlTest {

    @Test
    fun `escapeXml escapes special characters and strips invalid control chars`() {
        val raw = "A & B <tag> \"q\" 's'\u0000\u0007\nLine2"
        assertEquals("A &amp; B &lt;tag&gt; &quot;q&quot; &apos;s&apos;\nLine2", EwsXml.escapeXml(raw))
    }

    @Test
    fun `parseFoldersResponse parses folder counts`() {
        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:GetFolderResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                     xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:GetFolderResponseMessage ResponseClass="Success">
                      <m:ResponseCode>NoError</m:ResponseCode>
                      <m:Folders>
                        <t:Folder>
                          <t:DisplayName>Входящие</t:DisplayName>
                          <t:TotalCount>42</t:TotalCount>
                          <t:UnreadCount>3</t:UnreadCount>
                        </t:Folder>
                      </m:Folders>
                    </m:GetFolderResponseMessage>
                  </m:ResponseMessages>
                </m:GetFolderResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val folders = EwsXml.parseFoldersResponse(xml, listOf(MailFolderType.INBOX))
        assertEquals(1, folders.size)
        assertEquals("Входящие", folders[0].displayName)
        assertEquals(42, folders[0].totalCount)
        assertEquals(3, folders[0].unreadCount)
    }

    @Test
    fun `parseFindItemsResponse parses list and pagination flag`() {
        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="false">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="id-1" ChangeKey="ck-1"/>
                            <t:Subject>Расписание</t:Subject>
                            <t:DateTimeReceived>2026-11-26T08:00:00Z</t:DateTimeReceived>
                            <t:HasAttachments>true</t:HasAttachments>
                            <t:Preview>Добрый день...</t:Preview>
                            <t:From>
                              <t:Mailbox>
                                <t:Name>Деканат</t:Name>
                                <t:EmailAddress>dean@nstu.ru</t:EmailAddress>
                              </t:Mailbox>
                            </t:From>
                            <t:IsRead>false</t:IsRead>
                          </t:Message>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                  </m:ResponseMessages>
                </m:FindItemResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val (items, includesLast) = EwsXml.parseFindItemsResponse(xml)
        assertFalse(includesLast)
        assertEquals(1, items.size)
        assertEquals("id-1", items[0].id)
        assertEquals("Расписание", items[0].subject)
        assertEquals("Деканат", items[0].senderName)
        assertFalse(items[0].isRead)
        assertTrue(items[0].hasAttachments)
    }

    @Test
    fun `parseGetItemResponse and parseGetAttachmentResponse extract body and bytes`() {
        val itemXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:GetItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:GetItemResponseMessage ResponseClass="Success">
                      <m:Items>
                        <t:Message>
                          <t:ItemId Id="id-1" ChangeKey="ck-2"/>
                          <t:Subject>Тест</t:Subject>
                          <t:Body BodyType="HTML">&lt;p&gt;Привет&lt;/p&gt;</t:Body>
                          <t:DateTimeReceived>2026-11-26T08:10:00Z</t:DateTimeReceived>
                          <t:Attachments>
                            <t:FileAttachment>
                              <t:AttachmentId Id="att-9"/>
                              <t:Name>doc.pdf</t:Name>
                              <t:ContentType>application/pdf</t:ContentType>
                              <t:Size>1024</t:Size>
                              <t:IsInline>false</t:IsInline>
                            </t:FileAttachment>
                          </t:Attachments>
                          <t:ToRecipients>
                            <t:Mailbox>
                              <t:Name>Студент</t:Name>
                              <t:EmailAddress>student@stud.nstu.ru</t:EmailAddress>
                            </t:Mailbox>
                          </t:ToRecipients>
                          <t:From>
                            <t:Mailbox>
                              <t:Name>Преподаватель</t:Name>
                              <t:EmailAddress>prof@corp.nstu.ru</t:EmailAddress>
                            </t:Mailbox>
                          </t:From>
                          <t:IsRead>true</t:IsRead>
                        </t:Message>
                      </m:Items>
                    </m:GetItemResponseMessage>
                  </m:ResponseMessages>
                </m:GetItemResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val detail = EwsXml.parseGetItemResponse(itemXml)
        assertTrue(detail.isHtml)
        assertEquals("<p>Привет</p>", detail.body)
        assertEquals(1, detail.attachments.size)
        assertEquals("att-9", detail.attachments[0].id)
        assertEquals("doc.pdf", detail.attachments[0].name)

        val payload = "hello nstu".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(payload)
        val attXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:GetAttachmentResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                         xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:GetAttachmentResponseMessage ResponseClass="Success">
                      <m:Attachments>
                        <t:FileAttachment>
                          <t:Name>doc.pdf</t:Name>
                          <t:ContentType>application/pdf</t:ContentType>
                          <t:Content>$b64</t:Content>
                        </t:FileAttachment>
                      </m:Attachments>
                    </m:GetAttachmentResponseMessage>
                  </m:ResponseMessages>
                </m:GetAttachmentResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val downloaded = EwsXml.parseGetAttachmentResponse(attXml)
        assertEquals("doc.pdf", downloaded.name)
        assertArrayEquals(payload, downloaded.bytes)
    }

    @Test
    fun `parseSimpleSuccessResponse throws EwsException on EWS error`() {
        val errXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:CreateItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <m:ResponseMessages>
                    <m:CreateItemResponseMessage ResponseClass="Error">
                      <m:MessageText>Mailbox does not exist.</m:MessageText>
                      <m:ResponseCode>ErrorNonExistentMailbox</m:ResponseCode>
                    </m:CreateItemResponseMessage>
                  </m:ResponseMessages>
                </m:CreateItemResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        try {
            EwsXml.parseSimpleSuccessResponse(errXml)
            fail("Expected EwsException")
        } catch (e: EwsException) {
            assertTrue(e.message!!.contains("ErrorNonExistentMailbox"))
        }
    }

    @Test
    fun `normalizeHttpsUrl enforces strict https origin without userinfo`() {
        assertEquals("https://mail2.nstu.ru", EwsClient.normalizeHttpsUrl("mail2.nstu.ru"))
        assertEquals("https://mail2.nstu.ru", EwsClient.normalizeHttpsUrl("https://mail2.nstu.ru/owa/"))
        assertEquals("https://mail2.nstu.ru:8443", EwsClient.normalizeHttpsUrl("https://mail2.nstu.ru:8443/path"))

        listOf(
            "",
            "http://mail2.nstu.ru",
            "https://evil.com@mail2.nstu.ru",
            "javascript:alert(1)"
        ).forEach { invalid ->
            try {
                EwsClient.normalizeHttpsUrl(invalid)
                fail("Expected IllegalArgumentException for: $invalid")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
