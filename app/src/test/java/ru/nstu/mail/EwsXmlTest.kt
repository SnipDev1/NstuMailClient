package ru.nstu.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.EwsXml
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.OutgoingAttachment
import ru.nstu.mail.data.ParticipantRole
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.participants
import ru.nstu.mail.notify.mergeSeenMessageIds
import ru.nstu.mail.notify.selectNewMessagesToNotify
import ru.nstu.mail.ui.extractInitials
import ru.nstu.mail.ui.finalizeRecipientToken
import ru.nstu.mail.ui.formatShortPersonName
import ru.nstu.mail.ui.needsNameResolution
import ru.nstu.mail.ui.parseRecipientTokens
import ru.nstu.mail.ui.resolveDisplayName
import java.util.Base64

class EwsXmlTest {

    @Test
    fun `escapeXml escapes special characters and strips invalid control chars`() {
        val raw = "A & B <tag> \"q\" 's'\u0000\u0007\nLine2"
        assertEquals("A &amp; B &lt;tag&gt; &quot;q&quot; &apos;s&apos;\nLine2", EwsXml.escapeXml(raw))
    }

    @Test
    fun `parseFindFoldersResponse builds nested custom and system folder hierarchy`() {
        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindFolderResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                      xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindFolderResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Folders>
                          <t:Folder>
                            <t:FolderId Id="f-cfg" ChangeKey="ck0"/>
                            <t:ParentFolderId Id="root-1"/>
                            <t:FolderClass>IPF.Configuration</t:FolderClass>
                            <t:DisplayName>Conversation Action Settings</t:DisplayName>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-custom-sub" ChangeKey="ck3"/>
                            <t:ParentFolderId Id="f-custom"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Лабы</t:DisplayName>
                            <t:TotalCount>5</t:TotalCount>
                            <t:UnreadCount>2</t:UnreadCount>
                            <t:ChildFolderCount>0</t:ChildFolderCount>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-custom" ChangeKey="ck2"/>
                            <t:ParentFolderId Id="root-1"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Учёба</t:DisplayName>
                            <t:TotalCount>10</t:TotalCount>
                            <t:UnreadCount>1</t:UnreadCount>
                            <t:ChildFolderCount>1</t:ChildFolderCount>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-inbox" ChangeKey="ck1"/>
                            <t:ParentFolderId Id="root-1"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Входящие</t:DisplayName>
                            <t:TotalCount>42</t:TotalCount>
                            <t:UnreadCount>3</t:UnreadCount>
                            <t:ChildFolderCount>0</t:ChildFolderCount>
                          </t:Folder>
                        </t:Folders>
                      </m:RootFolder>
                    </m:FindFolderResponseMessage>
                  </m:ResponseMessages>
                </m:FindFolderResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val folders = EwsXml.parseFindFoldersResponse(
            xml = xml,
            rootFolderId = "root-1",
            systemIdMap = mapOf("f-inbox" to SystemFolderKind.INBOX)
        )
        assertEquals(3, folders.size)
        assertEquals("Входящие", folders[0].displayName)
        assertEquals(0, folders[0].depth)
        assertTrue(folders[0].isSystem)

        assertEquals("Учёба", folders[1].displayName)
        assertEquals(0, folders[1].depth)
        assertFalse(folders[1].isSystem)

        assertEquals("Лабы", folders[2].displayName)
        assertEquals(1, folders[2].depth)
        assertFalse(folders[2].isSystem)
    }

    @Test
    fun `parseFindItemsResponse parses flags importance and pagination`() {
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
                            <t:Importance>High</t:Importance>
                            <t:Flag><t:FlagStatus>Flagged</t:FlagStatus></t:Flag>
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
        assertTrue(items[0].isFlagged)
        assertEquals(EmailImportance.HIGH, items[0].importance)
        assertFalse(items[0].isRead)
        assertTrue(items[0].hasAttachments)

        val unreadReq = EwsXml.buildFindItemsRequest(listOf("f-1"), 0, 25, null, MailFilter.UNREAD)
        assertTrue(unreadReq.contains("""FieldURI="message:IsRead""""))
        val flagReq = EwsXml.buildFindItemsRequest(listOf("f-1"), 0, 25, "лаб", MailFilter.FLAGGED)
        assertTrue(flagReq.contains("лаб isflagged:true"))
    }

    @Test
    fun `parseGetItemResponse and parseGetAttachmentResponse extract body cid and bytes`() {
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
                          <t:Importance>High</t:Importance>
                          <t:IsDraft>true</t:IsDraft>
                          <t:Attachments>
                            <t:FileAttachment>
                              <t:AttachmentId Id="att-9"/>
                              <t:Name>doc.pdf</t:Name>
                              <t:ContentType>application/pdf</t:ContentType>
                              <t:ContentId>cid123</t:ContentId>
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
                          <t:BccRecipients>
                            <t:Mailbox>
                              <t:EmailAddress>bcc@stud.nstu.ru</t:EmailAddress>
                            </t:Mailbox>
                          </t:BccRecipients>
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
        assertTrue(detail.isDraft)
        assertEquals(EmailImportance.HIGH, detail.importance)
        assertEquals(listOf("bcc@stud.nstu.ru"), detail.bcc)
        assertEquals("cid123", detail.attachments[0].contentId)

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
    fun `parseResolveNamesResponse handles results and ErrorNameResolutionNoResults`() {
        val noResXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:ResolveNamesResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <m:ResponseMessages>
                    <m:ResolveNamesResponseMessage ResponseClass="Error">
                      <m:ResponseCode>ErrorNameResolutionNoResults</m:ResponseCode>
                    </m:ResolveNamesResponseMessage>
                  </m:ResponseMessages>
                </m:ResolveNamesResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertTrue(EwsXml.parseResolveNamesResponse(noResXml).isEmpty())

        val warnXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:ResolveNamesResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                        xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:ResolveNamesResponseMessage ResponseClass="Warning">
                      <m:ResponseCode>ErrorNameResolutionMultipleResults</m:ResponseCode>
                      <m:ResolutionSet>
                        <t:Resolution>
                          <t:Mailbox>
                            <t:Name>Иван Иванов</t:Name>
                            <t:EmailAddress>ivanov@corp.nstu.ru</t:EmailAddress>
                          </t:Mailbox>
                        </t:Resolution>
                      </m:ResolutionSet>
                    </m:ResolveNamesResponseMessage>
                  </m:ResponseMessages>
                </m:ResolveNamesResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val found = EwsXml.parseResolveNamesResponse(warnXml)
        assertEquals(1, found.size)
        assertEquals("ivanov@corp.nstu.ru", found[0].email)
    }

    @Test
    fun `parseCalendarEventsResponse and parseFindContactsResponse parse items`() {
        val calXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:CalendarItem>
                            <t:ItemId Id="ev-1" ChangeKey="ck-1"/>
                            <t:Subject>Лекция</t:Subject>
                            <t:Start>2026-11-27T03:00:00Z</t:Start>
                            <t:End>2026-11-27T04:30:00Z</t:End>
                            <t:Location>1-205</t:Location>
                            <t:IsAllDayEvent>false</t:IsAllDayEvent>
                          </t:CalendarItem>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                  </m:ResponseMessages>
                </m:FindItemResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val events = EwsXml.parseCalendarEventsResponse(calXml)
        assertEquals(1, events.size)
        assertEquals("Лекция", events[0].subject)
        assertEquals("1-205", events[0].location)

        val attReq = EwsXml.buildCreateAttachmentRequest(
            "item-1",
            "ck-1",
            listOf(OutgoingAttachment("note.txt", "text/plain", "hi".toByteArray()))
        )
        assertTrue(attReq.contains("<t:Name>note.txt</t:Name>"))
        assertTrue(attReq.contains("<t:Content>aGk=</t:Content>"))
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

    @Test
    fun `extractInitials and buildUserPhotoUrl handle names and emails`() {
        assertEquals("НЕ", extractInitials("Никулин Евгений Николаевич", "nikulin@corp.nstu.ru"))
        assertEquals("G", extractInitials("GitHub", "noreply@github.com"))
        assertEquals("S", extractInitials("", "student.2025@stud.nstu.ru"))

        val photoUrl = EwsClient.buildUserPhotoUrl("https://mail2.nstu.ru", "user@stud.nstu.ru")
        assertEquals("https", photoUrl.scheme)
        assertEquals("mail2.nstu.ru", photoUrl.host)
        assertEquals("/EWS/Exchange.asmx/s/GetUserPhoto", photoUrl.encodedPath)
        assertEquals("user@stud.nstu.ru", photoUrl.queryParameter("email"))
        assertEquals("HR96x96", photoUrl.queryParameter("size"))
    }

    @Test
    fun `formatShortPersonName participants and parsePersonProfilesFromResolveNames work`() {
        assertEquals("Никулин Е.Н.", formatShortPersonName("Никулин Евгений Николаевич", "nikulin@corp.nstu.ru"))
        assertEquals("Иванов П.", formatShortPersonName("Иванов Пётр", "ivanov@corp.nstu.ru"))
        assertEquals("GitHub", formatShortPersonName("GitHub", "noreply@github.com"))
        assertEquals("student.2025", formatShortPersonName("", "student.2025@stud.nstu.ru"))

        val detail = EmailDetail(
            id = "1",
            changeKey = "ck",
            subject = "S",
            from = "Преподаватель",
            fromEmail = "prof@corp.nstu.ru",
            to = listOf("Студент А.Б. <a@stud.nstu.ru>"),
            cc = listOf("b@stud.nstu.ru"),
            bcc = listOf("Скрытый <c@stud.nstu.ru>"),
            receivedAt = "",
            body = "",
            isHtml = false,
            isRead = true,
            attachments = emptyList()
        )
        val parts = detail.participants()
        assertEquals(4, parts.size)
        assertEquals(ParticipantRole.FROM, parts[0].role)
        assertEquals("Преподаватель", parts[0].name)
        assertEquals(ParticipantRole.CC, parts[1].role)
        assertEquals("b@stud.nstu.ru", parts[1].email)
        assertEquals(ParticipantRole.TO, parts[2].role)
        assertEquals("Студент А.Б.", parts[2].name)
        assertEquals("a@stud.nstu.ru", parts[2].email)
        assertEquals(ParticipantRole.BCC, parts[3].role)

        val adXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:ResolveNamesResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                        xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:ResolveNamesResponseMessage ResponseClass="Success">
                      <m:ResolutionSet>
                        <t:Resolution>
                          <t:Mailbox>
                            <t:Name>a.nikulin</t:Name>
                            <t:EmailAddress>a.nikulin@corp.nstu.ru</t:EmailAddress>
                          </t:Mailbox>
                          <t:Contact>
                            <t:DisplayName>Никулин Андрей Викторович</t:DisplayName>
                            <t:CompanyName>НГТУ</t:CompanyName>
                            <t:Department>Кафедра ВТ</t:Department>
                            <t:JobTitle>Доцент</t:JobTitle>
                            <t:OfficeLocation>1-305</t:OfficeLocation>
                            <t:EmailAddresses>
                              <t:Entry Key="EmailAddress1">SMTP:a.nikulin@corp.nstu.ru</t:Entry>
                              <t:Entry Key="EmailAddress2">smtp:nav@smc.nstu.ru</t:Entry>
                            </t:EmailAddresses>
                            <t:PhoneNumbers>
                              <t:Entry Key="BusinessPhone">+7-383-346-00-00</t:Entry>
                              <t:Entry Key="MobilePhone"/>
                            </t:PhoneNumbers>
                          </t:Contact>
                        </t:Resolution>
                      </m:ResolutionSet>
                    </m:ResolveNamesResponseMessage>
                  </m:ResponseMessages>
                </m:ResolveNamesResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val profiles = EwsXml.parsePersonProfilesFromResolveNames(adXml)
        assertEquals(1, profiles.size)
        assertEquals("Никулин Андрей Викторович", profiles[0].displayName)
        assertEquals("Доцент", profiles[0].jobTitle)
        assertEquals("Кафедра ВТ", profiles[0].department)
        assertEquals("1-305", profiles[0].officeLocation)
        assertEquals(listOf("nav@smc.nstu.ru"), profiles[0].aliases)
        assertEquals(listOf("Рабочий" to "+7-383-346-00-00"), profiles[0].phones)
    }

    @Test
    fun `parseRecipientTokens and finalizeRecipientToken split commas semicolons and keep search spaces`() {
        val (noneDone, searching) = parseRecipientTokens("Никулин Евгений")
        assertTrue(noneDone.isEmpty())
        assertEquals("Никулин Евгений", searching)

        val (done, rem) = parseRecipientTokens("a@nstu.ru, Никулин А.В. <b@corp.nstu.ru>; Петров")
        assertEquals(2, done.size)
        assertEquals("a@nstu.ru" to "a@nstu.ru", done[0])
        assertEquals("Никулин А.В." to "b@corp.nstu.ru", done[1])
        assertEquals("Петров", rem)

        assertEquals("Иван" to "ivan@nstu.ru", finalizeRecipientToken("Иван <ivan@nstu.ru>,"))
        assertNull(finalizeRecipientToken("без_собаки"))
    }

    @Test
    fun `findPeople recipientcache and resolveDisplayName resolve student FIO`() {
        val req = EwsXml.buildFindRecipientCacheRequest(50)
        assertTrue(req.contains("<m:FindPeople>"))
        assertTrue(req.contains("""Id="recipientcache""""))

        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <FindPeopleResponse ResponseClass="Success"
                                    xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <ResponseCode>NoError</ResponseCode>
                  <People>
                    <Persona xmlns="http://schemas.microsoft.com/exchange/services/2006/types">
                      <DisplayName>Иванов Пётр Сергеевич</DisplayName>
                      <EmailAddress>
                        <Name>Иванов Пётр Сергеевич</Name>
                        <EmailAddress>ivanov.2025@stud.nstu.ru</EmailAddress>
                        <RoutingType>SMTP</RoutingType>
                      </EmailAddress>
                    </Persona>
                    <Persona xmlns="http://schemas.microsoft.com/exchange/services/2006/types">
                      <DisplayName>Иванов Пётр Сергеевич</DisplayName>
                      <EmailAddress>
                        <Name>ivanov</Name>
                        <EmailAddress>IVANOV.2025@stud.nstu.ru</EmailAddress>
                      </EmailAddress>
                    </Persona>
                  </People>
                </FindPeopleResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val cache = EwsXml.parseFindPeopleResponse(xml)
        assertEquals(1, cache.size)
        assertEquals("Иванов Пётр Сергеевич", cache[0].name)
        assertEquals("ivanov.2025@stud.nstu.ru", cache[0].email)

        val errXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <FindPeopleResponse ResponseClass="Error"
                                    xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"/>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertTrue(EwsXml.parseFindPeopleResponse(errXml).isEmpty())

        assertTrue(needsNameResolution("", "a.nikulin@corp.nstu.ru"))
        assertTrue(needsNameResolution("a.nikulin", "a.nikulin@corp.nstu.ru"))
        assertTrue(needsNameResolution("a.nikulin@corp.nstu.ru", "a.nikulin@corp.nstu.ru"))
        assertFalse(needsNameResolution("Никулин Андрей Викторович", "a.nikulin@corp.nstu.ru"))

        val known = mapOf("ivanov.2025@stud.nstu.ru" to "Иванов Пётр Сергеевич")
        assertEquals(
            "Иванов Пётр Сергеевич",
            resolveDisplayName("ivanov.2025@stud.nstu.ru", "ivanov.2025@stud.nstu.ru", null, known)
        )
        assertEquals(
            "Никулин Андрей Викторович",
            resolveDisplayName(
                "a.nikulin",
                "a.nikulin@corp.nstu.ru",
                PersonProfile("Никулин Андрей Викторович", "a.nikulin@corp.nstu.ru"),
                emptyMap()
            )
        )
    }

    @Test
    fun `inbox unread request and notification baseline logic work properly`() {
        val req = EwsXml.buildFindInboxUnreadRequest(10)
        assertTrue(req.contains("""<t:DistinguishedFolderId Id="inbox"/>"""))
        assertTrue(req.contains("""FieldURI="message:IsRead""""))
        assertTrue(req.contains("""Value="false""""))

        val m1 = EmailSummary("id1", "c1", "Subj 1", "A", "a@nstu.ru", "", isRead = false, hasAttachments = false, preview = "")
        val m2 = EmailSummary("id2", "c2", "Subj 2", "B", "b@nstu.ru", "", isRead = false, hasAttachments = false, preview = "")

        assertTrue(selectNewMessagesToNotify(listOf(m1, m2), emptySet(), initialized = false).isEmpty())
        val notified = selectNewMessagesToNotify(listOf(m1, m2), setOf("id1"), initialized = true)
        assertEquals(listOf("id2"), notified.map { it.id })

        val merged = mergeSeenMessageIds(listOf("a", "b", "c"), listOf("b", "d", "e"), maxSize = 4)
        assertEquals(listOf("b", "c", "d", "e"), merged)
    }

    @Test
    fun `streaming subscription builders and event chunk parser handle NewMailEvent and statuses`() {
        val subReq = EwsXml.buildStreamingSubscribeRequest()
        assertTrue(subReq.contains("<m:StreamingSubscriptionRequest>"))
        assertTrue(subReq.contains("<t:EventType>NewMailEvent</t:EventType>"))

        val subResp = """
            <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
              <Body>
                <m:SubscribeResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <m:ResponseMessages>
                    <m:SubscribeResponseMessage ResponseClass="Success">
                      <m:ResponseCode>NoError</m:ResponseCode>
                      <m:SubscriptionId>SUB123</m:SubscriptionId>
                    </m:SubscribeResponseMessage>
                  </m:ResponseMessages>
                </m:SubscribeResponse>
              </Body>
            </Envelope>
        """.trimIndent()
        assertEquals("SUB123", EwsXml.parseSubscribeResponse(subResp))

        val getEvReq = EwsXml.buildGetStreamingEventsRequest("SUB123", 25)
        assertTrue(getEvReq.contains("<t:SubscriptionId>SUB123</t:SubscriptionId>"))
        assertTrue(getEvReq.contains("<m:ConnectionTimeout>25</m:ConnectionTimeout>"))

        val chunkXml = """
            <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
              <Body>
                <m:GetStreamingEventsResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:GetStreamingEventsResponseMessage ResponseClass="Success">
                      <m:ResponseCode>NoError</m:ResponseCode>
                      <m:Notifications>
                        <m:Notification>
                          <t:SubscriptionId>SUB123</t:SubscriptionId>
                          <t:NewMailEvent>
                            <t:ItemId Id="MSG1"/>
                          </t:NewMailEvent>
                          <t:NewMailEvent>
                            <t:ItemId Id="MSG2"/>
                          </t:NewMailEvent>
                        </m:Notification>
                      </m:Notifications>
                      <m:ConnectionStatus>OK</m:ConnectionStatus>
                    </m:GetStreamingEventsResponseMessage>
                  </m:ResponseMessages>
                </m:GetStreamingEventsResponse>
              </Body>
            </Envelope>
        """.trimIndent()
        val parsed = EwsXml.parseStreamingChunkEvents(chunkXml)
        assertEquals(listOf("MSG1", "MSG2"), parsed.newMailItemIds)
        assertFalse(parsed.connectionClosed)
        assertFalse(parsed.subscriptionInvalid)

        val closedXml = """
            <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
              <Body>
                <m:GetStreamingEventsResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <m:ResponseMessages>
                    <m:GetStreamingEventsResponseMessage ResponseClass="Error">
                      <m:ResponseCode>ErrorInvalidSubscription</m:ResponseCode>
                      <m:ConnectionStatus>Closed</m:ConnectionStatus>
                    </m:GetStreamingEventsResponseMessage>
                  </m:ResponseMessages>
                </m:GetStreamingEventsResponse>
              </Body>
            </Envelope>
        """.trimIndent()
        val closed = EwsXml.parseStreamingChunkEvents(closedXml)
        assertTrue(closed.connectionClosed)
        assertTrue(closed.subscriptionInvalid)
        assertTrue(closed.newMailItemIds.isEmpty())
    }
}
