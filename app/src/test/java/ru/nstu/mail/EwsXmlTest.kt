package ru.nstu.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailHtmlSanitizer
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
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.adjustFolderUnreadDelta
import ru.nstu.mail.data.applyUniqueSubtreeCounts
import ru.nstu.mail.data.childTagsForPrefix
import ru.nstu.mail.data.collectSubtreeFolderIds
import ru.nstu.mail.data.deduplicateSummaries
import ru.nstu.mail.data.extractAllTagHierarchiesForMatching
import ru.nstu.mail.data.extractAllTagsForMatching
import ru.nstu.mail.data.extractSubjectTagHierarchies
import ru.nstu.mail.data.extractSubjectTags
import ru.nstu.mail.data.formatSubjectWithTagHierarchies
import ru.nstu.mail.data.formatSubjectWithTags
import ru.nstu.mail.data.formatTagHierarchiesRaw
import ru.nstu.mail.data.matchAllAutoSortRules
import ru.nstu.mail.data.matchAutoSortRuleDetailed
import ru.nstu.mail.data.participants
import ru.nstu.mail.data.parseTagHierarchiesRaw
import ru.nstu.mail.data.shouldNotifyForMessage
import ru.nstu.mail.data.shouldRouteToUnsorted
import ru.nstu.mail.data.sortTagTemplatesHierarchically
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
        assertEquals(1, folders[1].unreadCount)
        assertEquals(10, folders[1].totalCount)
        assertFalse(folders[1].isSystem)

        assertEquals("Лабы", folders[2].displayName)
        assertEquals(1, folders[2].depth)
        assertEquals(2, folders[2].unreadCount)
        assertEquals(5, folders[2].totalCount)
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

    @Test
    fun `EmailHtmlSanitizer strips unsafe tags or attrs and adapts colors safely`() {
        val evilHtml = """
            <div style="background-color:#ffffff; background:url(javascript:alert(1)); position:fixed">
              <script>alert('xss')</script>
              <iframe src="https://evil.example"></iframe>
              <form action="/steal"><input type="text"/></form>
              <p onclick="evil()" style="color:#000000 !important; font-weight:bold">Привет</p>
              <span style="color:#4CAF50">Акцент</span>
              <table><tr><td colspan="2" bgcolor="#ffffff"><a href="https://nstu.ru">Сайт</a></td></tr></table>
              <a href="javascript:alert(1)">Плохая ссылка</a>
              <img src="data:image/png;base64,iVBORw0KGgo=" alt="ok"/>
              <img src="data:image/svg+xml;base64,PHN2Zy8+" alt="bad"/>
            </div>
        """.trimIndent()

        val darkDoc = EmailHtmlSanitizer.renderEmailDocument(
            body = evilHtml,
            isHtml = true,
            darkMode = true,
            bgHex = "#101512",
            textHex = "#E0E4E0",
            linkHex = "#5CDAA5"
        )
        assertFalse(darkDoc.contains("<script", ignoreCase = true))
        assertFalse(darkDoc.contains("<iframe", ignoreCase = true))
        assertFalse(darkDoc.contains("<form", ignoreCase = true))
        assertFalse(darkDoc.contains("onclick", ignoreCase = true))
        assertFalse(darkDoc.contains("javascript:", ignoreCase = true))
        assertFalse(darkDoc.contains("data:image/svg+xml", ignoreCase = true))
        assertFalse(darkDoc.contains("position:fixed", ignoreCase = true))
        assertFalse(darkDoc.contains("#000000", ignoreCase = true))
        assertFalse(darkDoc.contains("bgcolor", ignoreCase = true))
        assertTrue(darkDoc.contains("font-weight: bold"))
        assertTrue(darkDoc.contains("#4CAF50"))
        assertTrue(darkDoc.contains("<table>"))
        assertTrue(darkDoc.contains("""href="https://nstu.ru""""))
        assertTrue(darkDoc.contains("data:image/png;base64,iVBORw0KGgo="))

        val lightDoc = EmailHtmlSanitizer.renderEmailDocument(
            body = evilHtml,
            isHtml = true,
            darkMode = false,
            bgHex = "#FFFFFF",
            textHex = "#1B1B1B",
            linkHex = "#006B47"
        )
        assertTrue(lightDoc.contains("#000000"))
        assertTrue(lightDoc.contains("""bgcolor="#ffffff""""))
        assertFalse(lightDoc.contains("<script", ignoreCase = true))

        val plainDoc = EmailHtmlSanitizer.renderEmailDocument(
            body = "<b>Привет</b> & 1 < 2",
            isHtml = false,
            darkMode = true,
            bgHex = "#101512",
            textHex = "#E0E4E0",
            linkHex = "#5CDAA5"
        )
        assertTrue(plainDoc.contains("&lt;b&gt;Привет&lt;/b&gt;"))
    }

    @Test
    fun `subject tags, auto-sort rules, and notification filters work together`() {
        assertEquals(
            """Отчёт по работе ["1", "2"]""",
            formatSubjectWithTags("Отчёт по работе", listOf("1", "2"))
        )
        assertEquals(
            """Отчёт ["1", "2", "3"]""",
            formatSubjectWithTags("""Отчёт ["1"]""", listOf("1", "2", "3"))
        )
        assertEquals("Обычная тема", formatSubjectWithTags("Обычная тема", emptyList()))

        val (tags, clean) = extractSubjectTags("""Re: Отчёт ["1", "2"]""")
        assertEquals(listOf("1", "2"), tags)
        assertEquals("Re: Отчёт", clean)

        // Legacy fallback keeps working
        val (legacyTags, legacyClean) = extractSubjectTags("[ПЗ] Старый формат")
        assertEquals(listOf("ПЗ"), legacyTags)
        assertEquals("Старый формат", legacyClean)

        assertEquals(listOf("1", "2"), extractAllTagsForMatching("""Письмо ["1", "2"]"""))
        assertEquals(listOf("ПЗ", "Срочно"), extractAllTagsForMatching("Re: Отчёт #ПЗ/Срочно"))

        val rules = listOf(
            TagSortRule(tag = "1", targetFolderId = "f_1", targetFolderName = "Папка 1", autoCreateSubfolders = true),
            TagSortRule(tag = "1/2", targetFolderId = "f_1_2", targetFolderName = "Папка 2", autoCreateSubfolders = true)
        )
        val exactMatch = matchAutoSortRuleDetailed("""Задание ["1", "2"]""", rules)
        assertEquals("f_1_2", exactMatch?.rule?.targetFolderId)
        assertTrue(exactMatch?.remainingSubtags?.isEmpty() == true)

        val branchMatch = matchAutoSortRuleDetailed("""Задание ["1", "3", "А"]""", rules)
        assertEquals("f_1", branchMatch?.rule?.targetFolderId)
        assertEquals(listOf("3", "А"), branchMatch?.remainingSubtags)

        assertNull(matchAutoSortRuleDetailed("""Задание ["9"]""", rules))
        assertNull(matchAutoSortRuleDetailed("Обычное письмо Outlook", rules))

        val templates = sortTagTemplatesHierarchically(listOf("1/2", "1/3", "1", "Важно"))
        assertEquals(listOf("1", "Важно"), childTagsForPrefix(templates, emptyList()))
        assertEquals(listOf("2", "3"), childTagsForPrefix(templates, listOf("1")))

        // Sorted to muted folder -> no notification
        assertFalse(
            shouldNotifyForMessage(
                senderEmail = "prof@corp.nstu.ru",
                effectiveFolderId = "f_1_2",
                isInbox = false,
                savedInboxFolderId = "f_inbox",
                mutedFolderIds = setOf("f_1_2"),
                mutedSenders = emptySet()
            )
        )
        // Sorted to unmuted folder even if Inbox is muted -> notifies!
        assertTrue(
            shouldNotifyForMessage(
                senderEmail = "prof@corp.nstu.ru",
                effectiveFolderId = "f_1",
                isInbox = false,
                savedInboxFolderId = "f_inbox",
                mutedFolderIds = setOf("f_inbox", "inbox"),
                mutedSenders = emptySet()
            )
        )
        // Muted sender in unmuted folder -> no notification
        assertFalse(
            shouldNotifyForMessage(
                senderEmail = "Prof@corp.nstu.ru ",
                effectiveFolderId = "f_1",
                isInbox = false,
                savedInboxFolderId = "f_inbox",
                mutedFolderIds = emptySet(),
                mutedSenders = setOf("prof@corp.nstu.ru")
            )
        )
        // Normal message in unmuted Inbox -> notifies
        assertTrue(
            shouldNotifyForMessage(
                senderEmail = "prof@corp.nstu.ru",
                effectiveFolderId = "f_inbox",
                isInbox = true,
                savedInboxFolderId = "f_inbox",
                mutedFolderIds = setOf("f_1"),
                mutedSenders = emptySet()
            )
        )
    }

    @Test
    fun `deleted subfolders are filtered out and hard delete empty copy xml builders work`() {
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
                            <t:FolderId Id="f-deleted" ChangeKey="ck-d"/>
                            <t:ParentFolderId Id="root-1"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Удалённые</t:DisplayName>
                            <t:TotalCount>2</t:TotalCount>
                            <t:UnreadCount>0</t:UnreadCount>
                            <t:ChildFolderCount>1</t:ChildFolderCount>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-active" ChangeKey="ck-a"/>
                            <t:ParentFolderId Id="root-1"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Активная</t:DisplayName>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-trashed-sub" ChangeKey="ck-t1"/>
                            <t:ParentFolderId Id="f-deleted"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Старая подпапка</t:DisplayName>
                          </t:Folder>
                          <t:Folder>
                            <t:FolderId Id="f-trashed-nested" ChangeKey="ck-t2"/>
                            <t:ParentFolderId Id="f-trashed-sub"/>
                            <t:FolderClass>IPF.Note</t:FolderClass>
                            <t:DisplayName>Глубокая подпапка</t:DisplayName>
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
            systemIdMap = mapOf("f-deleted" to SystemFolderKind.DELETED)
        )
        assertEquals(listOf("Удалённые", "Активная"), folders.map { it.displayName })

        val hardDel = EwsXml.buildDeleteFolderRequest("f-1", "", hardDelete = true)
        assertTrue(hardDel.contains("""<m:DeleteFolder DeleteType="HardDelete">"""))

        val emptyReq = EwsXml.buildEmptyFolderRequest("f-deleted")
        assertTrue(emptyReq.contains("""DeleteSubFolders="true""""))

        val copyReq = EwsXml.buildCopyItemsRequest(listOf("item-1" to "ck-1"), "f-target")
        assertTrue(copyReq.contains("<m:CopyItem>"))
        assertTrue(copyReq.contains("""<t:FolderId Id="f-target"/>"""))
    }

    @Test
    fun `multi-hierarchy semicolon tags, multi-folder rules, and unsorted nstu routing work`() {
        val formatted = formatSubjectWithTagHierarchies(
            "Важное объявление",
            listOf(listOf("Информационное письмо"), listOf("Вопрос"))
        )
        assertEquals("""Важное объявление ["Информационное письмо"; "Вопрос"]""", formatted)

        val formattedMixed = formatSubjectWithTagHierarchies(
            "Отчёт",
            listOf(listOf("1", "2"), listOf("Вопрос"))
        )
        assertEquals("""Отчёт ["1", "2"; "Вопрос"]""", formattedMixed)

        val (hierarchies, clean) = extractSubjectTagHierarchies(formattedMixed)
        assertEquals("Отчёт", clean)
        assertEquals(listOf(listOf("1", "2"), listOf("Вопрос")), hierarchies)

        val rawSaved = formatTagHierarchiesRaw(hierarchies)
        assertEquals("1/2;Вопрос", rawSaved)
        assertEquals(hierarchies, parseTagHierarchiesRaw(rawSaved))

        assertEquals(
            listOf(listOf("Информационное письмо"), listOf("Вопрос")),
            extractAllTagHierarchiesForMatching(formatted)
        )

        val rules = listOf(
            TagSortRule(tag = "1", targetFolderId = "f_1", targetFolderName = "Папка 1", autoCreateSubfolders = true),
            TagSortRule(tag = "Вопрос", targetFolderId = "f_q", targetFolderName = "Вопросы", autoCreateSubfolders = false),
            TagSortRule(tag = "Дубль", targetFolderId = "f_q", targetFolderName = "Вопросы", autoCreateSubfolders = false)
        )

        val multiMatches = matchAllAutoSortRules("""Задание ["1", "3"; "Вопрос"]""", rules)
        assertEquals(2, multiMatches.size)
        assertEquals("f_1", multiMatches[0].rule.targetFolderId)
        assertEquals(listOf("3"), multiMatches[0].remainingSubtags)
        assertEquals("f_q", multiMatches[1].rule.targetFolderId)
        assertTrue(multiMatches[1].remainingSubtags.isEmpty())

        // Duplicate target with identical effective destination is deduplicated
        val dedupedMatches = matchAllAutoSortRules("""Тест ["Вопрос"; "Дубль"]""", rules)
        assertEquals(1, dedupedMatches.size)

        // Unmatched tagged NSTU sender (substring "nstu.ru") -> goes to Unsorted
        assertTrue(shouldRouteToUnsorted("""Письмо ["Неизвестный"]""", 0, "dean@corp.nstu.ru"))
        assertTrue(shouldRouteToUnsorted("""Письмо ["Неизвестный"]""", 0, "user@nstu.ru"))
        assertTrue(shouldRouteToUnsorted("""Письмо ["Неизвестный"]""", 0, "s@nstu.ru.example.org"))
        // Matched at least one rule -> NOT Unsorted
        assertFalse(shouldRouteToUnsorted("""Письмо ["Вопрос"]""", 1, "dean@corp.nstu.ru"))
        // External sender without nstu.ru -> stays in Inbox (NOT Unsorted)
        assertFalse(shouldRouteToUnsorted("""Письмо ["Неизвестный"]""", 0, "news@gmail.com"))
        // Untagged NSTU email -> stays in Inbox
        assertFalse(shouldRouteToUnsorted("Обычное письмо", 0, "dean@corp.nstu.ru"))
    }

    @Test
    fun `FindItem requests 0x5D01 and extracts SMTP fallback when Mailbox EmailAddress is missing or X500`() {
        val findReq = EwsXml.buildFindItemsRequest(listOf("f-inbox"), 0, 25)
        assertTrue(findReq.contains("""PropertyTag="0x5D01""""))
        val unreadReq = EwsXml.buildFindInboxUnreadRequest(15)
        assertTrue(unreadReq.contains("""PropertyTag="0x5D01""""))

        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="m-1" ChangeKey="ck-1"/>
                            <t:Subject>Тест ["Б1"]</t:Subject>
                            <t:DateTimeReceived>2026-11-26T15:36:00Z</t:DateTimeReceived>
                            <t:ExtendedProperty>
                              <t:ExtendedFieldURI PropertyTag="0x5d01" PropertyType="String"/>
                              <t:Value>student@stud.nstu.ru</t:Value>
                            </t:ExtendedProperty>
                            <t:From>
                              <t:Mailbox>
                                <t:Name>Студент</t:Name>
                              </t:Mailbox>
                            </t:From>
                            <t:IsRead>true</t:IsRead>
                          </t:Message>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                  </m:ResponseMessages>
                </m:FindItemResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val (items, _) = EwsXml.parseFindItemsResponse(xml)
        assertEquals(1, items.size)
        assertEquals("student@stud.nstu.ru", items[0].senderEmail)
    }

    @Test
    fun `adjustFolderUnreadDelta updates child parent and grandparent unread counts`() {
        val folders = listOf(
            ru.nstu.mail.data.MailFolder("f-root", "", "", "Учёба", totalCount = 20, unreadCount = 5, childFolderCount = 1, depth = 0),
            ru.nstu.mail.data.MailFolder("f-sub", "", "f-root", "ПЗ", totalCount = 10, unreadCount = 3, childFolderCount = 1, depth = 1),
            ru.nstu.mail.data.MailFolder("f-leaf", "", "f-sub", "Лаба 1", totalCount = 4, unreadCount = 1, childFolderCount = 0, depth = 2),
            ru.nstu.mail.data.MailFolder("f-sib", "", "f-root", "Другое", totalCount = 5, unreadCount = 2, childFolderCount = 0, depth = 1)
        )

        val afterRead = adjustFolderUnreadDelta(folders, "f-leaf", -1)
        assertEquals(4, afterRead.first { it.id == "f-root" }.unreadCount)
        assertEquals(2, afterRead.first { it.id == "f-sub" }.unreadCount)
        assertEquals(0, afterRead.first { it.id == "f-leaf" }.unreadCount)
        assertEquals(2, afterRead.first { it.id == "f-sib" }.unreadCount)

        // Does not drop below zero
        val clamped = adjustFolderUnreadDelta(afterRead, "f-leaf", -5)
        assertEquals(0, clamped.first { it.id == "f-leaf" }.unreadCount)
        assertEquals(0, clamped.first { it.id == "f-root" }.unreadCount)
    }

    @Test
    fun `applyUniqueSubtreeCounts deduplicates messages across parent and child folders`() {
        val req = EwsXml.buildFindFolderMessageKeysRequest(listOf("f-parent", "f-child"))
        assertTrue(req.contains("""FieldURI="message:InternetMessageId""""))
        assertTrue(req.contains("""<t:FolderId Id="f-parent"/>"""))
        assertTrue(req.contains("""<t:FolderId Id="f-child"/>"""))

        val xml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="i1"/>
                            <t:Subject>Первое</t:Subject>
                            <t:DateTimeReceived>2026-11-26T10:00:00Z</t:DateTimeReceived>
                            <t:IsRead>false</t:IsRead>
                            <t:InternetMessageId>&lt;m1@nstu.ru&gt;</t:InternetMessageId>
                          </t:Message>
                          <t:Message>
                            <t:ItemId Id="i2-parent"/>
                            <t:Subject>Общее</t:Subject>
                            <t:DateTimeReceived>2026-11-26T11:00:00Z</t:DateTimeReceived>
                            <t:IsRead>false</t:IsRead>
                            <t:InternetMessageId>&lt;m2@nstu.ru&gt;</t:InternetMessageId>
                          </t:Message>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="i2-child"/>
                            <t:Subject>Общее</t:Subject>
                            <t:DateTimeReceived>2026-11-26T11:00:00Z</t:DateTimeReceived>
                            <t:IsRead>true</t:IsRead>
                            <t:InternetMessageId>&lt;m2@nstu.ru&gt;</t:InternetMessageId>
                          </t:Message>
                          <t:Message>
                            <t:ItemId Id="i3-child"/>
                            <t:Subject>Только в дочерней</t:Subject>
                            <t:DateTimeReceived>2026-11-26T12:00:00Z</t:DateTimeReceived>
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

        val keysByFolder = EwsXml.parseFindFolderMessageKeysResponse(xml, listOf("f-parent", "f-child"))
        assertEquals(2, keysByFolder.size)

        val rawFolders = listOf(
            ru.nstu.mail.data.MailFolder("f-inbox", "", "", "Входящие", totalCount = 9, unreadCount = 4, childFolderCount = 0, depth = 0, systemKind = SystemFolderKind.INBOX),
            ru.nstu.mail.data.MailFolder("f-parent", "", "", "Учёба", totalCount = 2, unreadCount = 2, childFolderCount = 1, depth = 0),
            ru.nstu.mail.data.MailFolder("f-child", "", "f-parent", "ПЗ", totalCount = 2, unreadCount = 1, childFolderCount = 0, depth = 1)
        )
        val merged = applyUniqueSubtreeCounts(rawFolders, keysByFolder)

        // Inbox untouched
        assertEquals(9, merged[0].totalCount)
        assertEquals(4, merged[0].unreadCount)
        // Parent deduplicates M1 (unread), M2 (read in child -> read in parent), M3 (unread) => 2/3
        assertEquals(3, merged[1].totalCount)
        assertEquals(2, merged[1].unreadCount)
        // Leaf child keeps own counts => 1/2
        assertEquals(2, merged[2].totalCount)
        assertEquals(1, merged[2].unreadCount)
    }

    @Test
    fun `collectSubtreeFolderIds and deduplicateSummaries merge multi-folder child views cleanly`() {
        val folders = listOf(
            ru.nstu.mail.data.MailFolder("f-parent", "", "", "Учёба", 1, 0, 1, 0),
            ru.nstu.mail.data.MailFolder("f-child", "", "f-parent", "ПЗ", 1, 0, 0, 1),
            ru.nstu.mail.data.MailFolder("f-grand", "", "f-child", "Лаба 1", 1, 0, 0, 2),
            ru.nstu.mail.data.MailFolder("f-other", "", "", "Важно", 1, 0, 0, 0)
        )
        assertEquals(listOf("f-parent", "f-child", "f-grand"), collectSubtreeFolderIds(folders, "f-parent"))
        assertEquals(listOf("f-other"), collectSubtreeFolderIds(folders, "f-other"))

        val findReq = EwsXml.buildFindItemsRequest(listOf("f-parent", "f-child"), 0, 25)
        assertTrue(findReq.contains("""FieldURI="message:InternetMessageId""""))

        val multiRespXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <m:FindItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                  <m:ResponseMessages>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="m-parent-copy" ChangeKey="ck1"/>
                            <t:Subject>Общее ["1", "2"]</t:Subject>
                            <t:DateTimeReceived>2026-11-26T10:00:00Z</t:DateTimeReceived>
                            <t:InternetMessageId>&lt;same@nstu.ru&gt;</t:InternetMessageId>
                            <t:IsRead>false</t:IsRead>
                          </t:Message>
                        </t:Items>
                      </m:RootFolder>
                    </m:FindItemResponseMessage>
                    <m:FindItemResponseMessage ResponseClass="Success">
                      <m:RootFolder IncludesLastItemInRange="true">
                        <t:Items>
                          <t:Message>
                            <t:ItemId Id="m-child-copy" ChangeKey="ck2"/>
                            <t:Subject>Общее ["1", "2"]</t:Subject>
                            <t:DateTimeReceived>2026-11-26T10:00:00Z</t:DateTimeReceived>
                            <t:InternetMessageId>&lt;same@nstu.ru&gt;</t:InternetMessageId>
                            <t:IsRead>true</t:IsRead>
                          </t:Message>
                          <t:Message>
                            <t:ItemId Id="m-leaf-only" ChangeKey="ck3"/>
                            <t:Subject>Из подпапки</t:Subject>
                            <t:DateTimeReceived>2026-11-26T12:00:00Z</t:DateTimeReceived>
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

        val (items, _) = EwsXml.parseFindItemsResponse(multiRespXml)
        assertEquals(2, items.size)
        assertEquals("Из подпапки", items[0].subject)
        assertEquals("Общее [\"1\", \"2\"]", items[1].subject)
        assertTrue(items[1].isRead)

        // Fallback deduplication without InternetMessageId
        val a = EmailSummary("id1", "c1", "Тема", "A", "a@nstu.ru", "2026-11-26T09:00:00Z", isRead = false, hasAttachments = false, preview = "")
        val b = EmailSummary("id2", "c2", "Тема", "A", "a@nstu.ru", "2026-11-26T09:00:00Z", isRead = true, hasAttachments = false, preview = "")
        val deduped = deduplicateSummaries(listOf(a, b))
        assertEquals(1, deduped.size)
        assertTrue(deduped[0].isRead)
    }
}
