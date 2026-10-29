package com.shtong.gateway;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/v1")
@Validated
public class GatewayController {
    private final AccountAccessService service;
    @Value("${shtong.cookie-secure:true}") private boolean cookieSecure;
    private final AdministrativeDivisionService divisions;
    public GatewayController(AccountAccessService service, AdministrativeDivisionService divisions) { this.service = service; this.divisions = divisions; }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, @RequestHeader(value="X-Request-Id", required=false) String rid, HttpServletRequest request,HttpServletResponse response) {
        String requestId = requestId(rid); var result = service.login(body.loginName(), body.password(), body.channel(), requestId,request.getRemoteAddr()); setSessionCookie(result, response); return ResponseEntity.ok(result);
    }
    @PostMapping("/auth/token/refresh")
    ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest req,HttpServletResponse response, @RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key, @RequestHeader(value="X-Request-Id", required=false) String rid) { Map<String,Object> result=new LinkedHashMap<>(service.refresh(req,body.refreshToken(),body.channel(),key,requestId(rid)));setSessionCookies(result,response);return ResponseEntity.ok(result); }
    @PostMapping("/auth/password/change")
    ResponseEntity<?> changePassword(@Valid @RequestBody PasswordChangeRequest body, HttpServletRequest req, HttpServletResponse response, @RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key, @RequestHeader(value="X-Request-Id", required=false) String rid) { Map<String,Object> result=new LinkedHashMap<>(service.changePassword(req,body.currentPassword(),body.newPassword(),key,requestId(rid)));setSessionCookies(result,response);return ResponseEntity.ok(result); }
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res, @RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key, @RequestHeader(value="X-Request-Id", required=false) String rid) { service.logout(req,key,requestId(rid));clearCookie("SHTONG_SESSION",true,res);clearCookie("SHTONG_REFRESH",true,res);clearCookie("SHTONG_CSRF",false,res);return ResponseEntity.noContent().build(); }
    @PostMapping("/modules/medical-insurance/sso/ticket")
    ResponseEntity<?> medicalInsuranceTicket(HttpServletRequest req, @RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key, @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.issueMedicalInsuranceTicket(req, key, requestId(rid)));
    }
    @PostMapping("/modules/medical-insurance/sso/exchange")
    ResponseEntity<?> medicalInsuranceExchange(@Valid @RequestBody SsoExchangeRequest body,
                                               @RequestHeader("X-Shtong-Adapter-Id") @NotBlank @Size(max=100) String adapterId,
                                               @RequestHeader("X-Shtong-Adapter-Secret") @NotBlank @Size(max=512) String adapterSecret,
                                               @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.exchangeMedicalInsuranceTicket(body.ticket(), body.audience(), adapterId, adapterSecret, requestId(rid)));
    }
    @PostMapping("/modules/medical-insurance/sso/introspect")
    ResponseEntity<?> medicalInsuranceIntrospect(@Valid @RequestBody SsoIntrospectRequest body,
                                                 @RequestHeader("X-Shtong-Adapter-Id") @NotBlank @Size(max=100) String adapterId,
                                                 @RequestHeader("X-Shtong-Adapter-Secret") @NotBlank @Size(max=512) String adapterSecret,
                                                 @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.introspectMedicalInsuranceSession(body.sessionToken(), adapterId, adapterSecret, requestId(rid)));
    }
    @PostMapping("/modules/medical-insurance/sso/revoke")
    ResponseEntity<?> medicalInsuranceRevoke(@Valid @RequestBody SsoIntrospectRequest body,
                                             @RequestHeader("X-Shtong-Adapter-Id") @NotBlank @Size(max=100) String adapterId,
                                             @RequestHeader("X-Shtong-Adapter-Secret") @NotBlank @Size(max=512) String adapterSecret,
                                             @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.revokeMedicalInsuranceSession(body.sessionToken(), adapterId, adapterSecret, requestId(rid)));
    }
    @PostMapping("/modules/medical-insurance/internal/review-subject")
    ResponseEntity<?> medicalReviewSubject(@Valid @RequestBody ReviewSubjectRequest body,
            @RequestHeader("X-Shtong-Adapter-Id") String adapterId,
            @RequestHeader("X-Shtong-Adapter-Secret") String adapterSecret,
            @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.medicalReviewSubject(body.accountId(),adapterId,adapterSecret,requestId(rid)));
    }
    @PostMapping("/modules/medical-insurance/internal/divisions")
    ResponseEntity<?> medicalReviewDivisions(@Valid @RequestBody ReviewDivisionsRequest body,
            @RequestHeader("X-Shtong-Adapter-Id") String adapterId,
            @RequestHeader("X-Shtong-Adapter-Secret") String adapterSecret,
            @RequestHeader(value="X-Request-Id", required=false) String rid) {
        return ResponseEntity.ok(service.medicalReviewDivisions(body.level(),body.parentCode(),adapterId,adapterSecret,requestId(rid)));
    }
    public record ReviewSubjectRequest(@NotNull UUID accountId) {}
    public record ReviewDivisionsRequest(@NotBlank @Pattern(regexp="PROVINCE|CITY|DISTRICT") String level,@Pattern(regexp="[0-9]{12}") String parentCode) {}

    @GetMapping("/session/bootstrap")
    ResponseEntity<?> bootstrap(HttpServletRequest req,@RequestHeader(value="X-Request-Id", required=false) String rid){return ResponseEntity.ok(service.bootstrap(req,requestId(rid)));}
    @PostMapping("/session/context")
    ResponseEntity<?> context(@Valid @RequestBody ContextRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128) String key,@RequestHeader(value="X-Request-Id", required=false) String rid){return ResponseEntity.ok(service.switchContext(req,body.workspace(),key,requestId(rid)));}

    @GetMapping("/profile") ResponseEntity<?> profile(HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.getProfile(req,requestId(rid)));}
    @GetMapping("/reference/administrative-divisions") ResponseEntity<?> administrativeDivisions(@RequestParam @Pattern(regexp="PROVINCE|CITY|DISTRICT") String level,@RequestParam(required=false)String parentCode,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.administrativeDivisions(req,level,parentCode,requestId(rid)));}
    @PostMapping("/profile/completion") ResponseEntity<?> completeProfile(@Valid @RequestBody ProfileCompletionRequest body,HttpServletRequest req,HttpServletResponse response,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){preResolve(body.currentAddress());preResolve(body.householdAddress());Map<String,Object> result=new LinkedHashMap<>(service.completeProfile(req,body.idCardNumber(),body.mobileNumber(),body.ethnicity(),addressMap(body.currentAddress()),addressMap(body.householdAddress()),key,requestId(rid)));setSessionCookies(result,response);return ResponseEntity.ok(result);}
    @PostMapping("/profile/sensitive-fields/{field}/reveal") ResponseEntity<?> revealProfileField(@PathVariable @Pattern(regexp="ID_CARD_NUMBER|MOBILE_NUMBER|CURRENT_ADDRESS|HOUSEHOLD_ADDRESS")String field,@Valid @RequestBody SensitiveFieldRevealRequest body,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.revealSensitiveProfileField(req,field,body.password(),requestId(rid)));}

    @GetMapping("/system/accounts") ResponseEntity<?> accounts(HttpServletRequest req,@RequestParam Map<String,String> filters,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.listAccounts(req,filters,requestId(rid)));}
    @PostMapping("/system/accounts") ResponseEntity<?> createAccount(@Valid @RequestBody AccountCreateRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.status(HttpStatus.CREATED).body(service.createAccount(req,body.loginName(),body.displayName(),body.personNo(),body.gender(),body.classId(),initialRoleMaps(body.roleAssignments()),body.reason(),key,requestId(rid)));}
    @GetMapping("/system/accounts/{accountId}") ResponseEntity<?> account(@PathVariable UUID accountId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.accountDetail(req,accountId,requestId(rid)));}
    @PatchMapping("/system/accounts/{accountId}") ResponseEntity<?> accountUpdate(@PathVariable UUID accountId,@Valid @RequestBody AccountUpdateRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){preResolve(body.currentAddress());preResolve(body.householdAddress());return ResponseEntity.ok(service.updateAccount(req,accountId,body.loginName(),body.displayName(),body.gender(),body.ethnicity(),body.idCardNumber(),body.mobileNumber(),addressMap(body.currentAddress()),addressMap(body.householdAddress()),body.expectedVersion(),body.reason(),key,requestId(rid)));}
    @PostMapping("/system/accounts/{accountId}/enrollment-change") ResponseEntity<?> enrollmentChange(@PathVariable UUID accountId,@Valid @RequestBody EnrollmentChangeRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.changeEnrollment(req,accountId,body.classId(),body.expectedVersion(),body.reason(),key,requestId(rid)));}
    @GetMapping("/system/accounts/{accountId}/authorization") ResponseEntity<?> authorization(@PathVariable UUID accountId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.authorization(req,accountId,requestId(rid)));}
    @GetMapping("/system/accounts/{accountId}/role-assignments") ResponseEntity<?> assignments(@PathVariable UUID accountId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.assignments(req,accountId,requestId(rid)));}
    @PostMapping("/system/accounts/{accountId}/role-assignments") ResponseEntity<?> createAssignment(@PathVariable UUID accountId,@Valid @RequestBody RoleAssignmentRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){Map<String,Object> result=service.createAssignment(req,accountId,body.roleCode(),body.scopeCeilingOrgUnitIds(),body.validFrom(),body.validTo(),body.reason(),key,requestId(rid));return ResponseEntity.status("PENDING_APPROVAL".equals(result.get("state"))?HttpStatus.ACCEPTED:HttpStatus.CREATED).body(result);}
    @PostMapping("/system/role-assignments/{assignmentId}/revoke") ResponseEntity<?> revokeAssignment(@PathVariable UUID assignmentId,@Valid @RequestBody ReasonRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){Map<String,Object> result=service.revokeAssignment(req,assignmentId,body.reason(),key,requestId(rid));return "PENDING_APPROVAL".equals(result.get("state"))?ResponseEntity.accepted().body(result):ResponseEntity.noContent().build();}

    @PostMapping("/system/accounts/commands/reset-password") ResponseEntity<?> reset(@Valid @RequestBody BulkCommandRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.command(req,"RESET",body.accountIds(),body.expectedCount(),body.reason(),key,requestId(rid)));}
    @PostMapping("/system/accounts/commands/delete") ResponseEntity<?> delete(@Valid @RequestBody BulkCommandRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.command(req,"DELETE",body.accountIds(),body.expectedCount(),body.reason(),key,requestId(rid)));}
    @PostMapping("/system/accounts/commands/graduate") ResponseEntity<?> graduate(@Valid @RequestBody BulkCommandRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.command(req,"GRADUATE",body.accountIds(),body.expectedCount(),body.reason(),key,requestId(rid)));}
    @GetMapping("/system/account-commands/{commandId}") ResponseEntity<?> command(@PathVariable UUID commandId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.commandStatus(req,commandId,requestId(rid)));}
    @PostMapping("/system/account-commands/{commandId}/credential-ticket") ResponseEntity<?> commandTicket(@PathVariable UUID commandId,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.credentialTicket(req,commandId,key,requestId(rid)));}

    @GetMapping(value="/system/account-import/template", produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ResponseEntity<byte[]> template(HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=shtong-account-import-v1.xlsx").body(service.template(req,requestId(rid)));}
    @PostMapping(value="/system/account-import/jobs", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) ResponseEntity<?> importJob(@RequestPart("file")MultipartFile file,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.createImport(req,file,key,requestId(rid)));}
    @GetMapping("/system/account-import/jobs/{jobId}") ResponseEntity<?> importStatus(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.importStatus(req,jobId,requestId(rid)));}
    @GetMapping("/system/account-import/jobs/{jobId}/errors") ResponseEntity<?> importErrors(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.importErrors(req,jobId,requestId(rid)));}
    @PostMapping("/system/account-import/jobs/{jobId}/commit") ResponseEntity<?> commitImport(@PathVariable UUID jobId,@Valid @RequestBody ImportCommitRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.commitImport(req,jobId,body.validatedRevision(),body.expectedAccountCount(),body.reason(),key,requestId(rid)));}
    @PostMapping("/system/account-import/jobs/{jobId}/credential-ticket") ResponseEntity<?> importTicket(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.importTicket(req,jobId,key,requestId(rid)));}

    @PostMapping("/system/account-export/jobs") ResponseEntity<?> export(@Valid @RequestBody AccountExportRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.createExport(req,exportMap(body),key,requestId(rid)));}
    @GetMapping("/system/account-export/jobs/{jobId}") ResponseEntity<?> exportStatus(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.exportStatus(req,jobId,requestId(rid)));}
    @PostMapping("/system/account-export/jobs/{jobId}/download-ticket") ResponseEntity<?> exportTicket(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.downloadTicket(req,jobId,key,requestId(rid)));}
    @GetMapping("/system/downloads/{ticketId}") ResponseEntity<byte[]> download(@PathVariable UUID ticketId,@RequestParam String token,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){var payload=service.consumeDownload(req,ticketId,token,requestId(rid));return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE,payload.mediaType()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+payload.fileName()+"\"").body(payload.content());}

    @GetMapping("/system/roles") ResponseEntity<?> roles(HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.roles(req,requestId(rid)));}
    @GetMapping("/system/control-requests") ResponseEntity<?> controls(@RequestParam(required=false)@Pattern(regexp="PENDING|APPROVED|REJECTED|EXPIRED|EXECUTED|INVALIDATED")String state,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="50")@Min(1)@Max(200)int limit,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.controlList(req,state,cursor,limit,requestId(rid)));}
    @GetMapping("/system/control-requests/{id}") ResponseEntity<?> control(@PathVariable UUID id,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.controlDetail(req,id,requestId(rid)));}
    @PostMapping("/system/control-requests/{id}/approve") ResponseEntity<?> approve(@PathVariable UUID id,@Valid @RequestBody ControlDecisionRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.controlDecision(req,id,body.expectedVersion(),body.reason(),true,key,requestId(rid)));}
    @PostMapping("/system/control-requests/{id}/reject") ResponseEntity<?> reject(@PathVariable UUID id,@Valid @RequestBody ControlDecisionRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.controlDecision(req,id,body.expectedVersion(),body.reason(),false,key,requestId(rid)));}

    @GetMapping("/system/org-units") ResponseEntity<?> orgs(@RequestParam(required=false)UUID parentId,@RequestParam(required=false)@Min(1)Long revision,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.orgList(req,parentId,revision,requestId(rid)));}
    @PostMapping("/system/org-units") ResponseEntity<?> orgCreate(@Valid @RequestBody OrgWriteRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.status(HttpStatus.CREATED).body(service.orgCreate(req,body.type(),body.parentId(),body.code(),body.name(),body.expectedVersion(),body.reason(),key,requestId(rid)));}
    @PatchMapping("/system/org-units/{id}") ResponseEntity<?> orgUpdate(@PathVariable UUID id,@Valid @RequestBody OrgWriteRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.orgUpdate(req,id,body.type(),body.parentId(),body.code(),body.name(),body.expectedVersion(),body.reason(),key,requestId(rid)));}
    @DeleteMapping("/system/org-units/{id}") ResponseEntity<Void> orgDelete(@PathVariable UUID id,@Valid @RequestBody ReasonRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key") @Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){service.orgDelete(req,id,body.reason(),key,requestId(rid));return ResponseEntity.noContent().build();}

    @GetMapping("/system/audit/events") ResponseEntity<?> audit(@RequestParam Instant occurredFrom,@RequestParam Instant occurredTo,@RequestParam(required=false)String eventCode,@RequestParam(required=false)UUID actorAccountId,@RequestParam(required=false)UUID subjectAccountId,@RequestParam(required=false)String requestId,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="50")@Min(1)@Max(200)int limit,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){Map<String,String> filters=new LinkedHashMap<>();filters.put("occurredFrom",occurredFrom.toString());filters.put("occurredTo",occurredTo.toString());put(filters,"eventCode",eventCode);put(filters,"actorAccountId",actorAccountId==null?null:actorAccountId.toString());put(filters,"subjectAccountId",subjectAccountId==null?null:subjectAccountId.toString());put(filters,"requestId",requestId);put(filters,"cursor",cursor);filters.put("limit",String.valueOf(limit));return ResponseEntity.ok(service.auditSearch(req,filters,requestId(rid)));}
    @GetMapping("/system/audit/events/{id}") ResponseEntity<?> auditDetail(@PathVariable UUID id,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.auditDetail(req,id,requestId(rid)));}
    @PostMapping("/system/audit/export-jobs") ResponseEntity<?> auditExport(@Valid @RequestBody AuditExportRequest body,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.accepted().body(service.auditExport(req,auditExportMap(body),key,requestId(rid)));}
    @GetMapping("/system/audit/export-jobs/{jobId}") ResponseEntity<?> auditExportStatus(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.auditExportStatus(req,jobId,requestId(rid)));}
    @PostMapping("/system/audit/export-jobs/{jobId}/download-ticket") ResponseEntity<?> auditExportTicket(@PathVariable UUID jobId,HttpServletRequest req,@RequestHeader("Idempotency-Key")@Size(min=16,max=128)String key,@RequestHeader(value="X-Request-Id",required=false)String rid){return ResponseEntity.ok(service.auditDownloadTicket(req,jobId,key,requestId(rid)));}

    private static String requestId(String supplied){return RequestIds.current(supplied);}
    /** Resolves administrative divisions before the state lock is taken, so a slow upstream never blocks other requests. */
    private void preResolve(AddressRequest value){if(value!=null)divisions.resolve(value.provinceCode(),value.cityCode(),value.districtCode(),value.detail());}
    private void setSessionCookie(AccountAccessService.LoginResult result,HttpServletResponse response){if(result.transportToken()==null||result.session()==null||!"HTTP_ONLY_COOKIE".equals(result.session().get("tokenDelivery")))return;setWebCookies(result.transportToken(),result.transportRefreshToken(),response);}
    private void setSessionCookies(Map<String,Object> result,HttpServletResponse response){String access=(String)result.remove("_transportToken");String refresh=(String)result.remove("_transportRefreshToken");Object delivery=result.get("tokenDelivery");if(delivery==null&&result.get("session") instanceof Map<?,?> session)delivery=session.get("tokenDelivery");if("HTTP_ONLY_COOKIE".equals(delivery))setWebCookies(access,refresh,response);}
    private void setWebCookies(String access,String refresh,HttpServletResponse response){if(access==null||refresh==null)return;addCookie("SHTONG_SESSION",access,true,null,response);addCookie("SHTONG_REFRESH",refresh,true,null,response);addCookie("SHTONG_CSRF",UUID.randomUUID().toString(),false,null,response);}
    private void addCookie(String name,String value,boolean httpOnly,Integer maxAge,HttpServletResponse response){Cookie c=new Cookie(name,value);c.setHttpOnly(httpOnly);c.setSecure(cookieSecure);c.setPath("/");if(maxAge!=null)c.setMaxAge(maxAge);c.setAttribute("SameSite","Strict");response.addCookie(c);}
    private void clearCookie(String name,boolean httpOnly,HttpServletResponse response){addCookie(name,"",httpOnly,0,response);}

    public record LoginRequest(@NotBlank @Size(max=100) String loginName,@NotBlank @Size(max=128) String password,@NotBlank @Pattern(regexp="WEB_USER|WEB_ADMIN|ANDROID|IOS") String channel) {}
    public record RefreshRequest(@NotBlank @Pattern(regexp="WEB_USER|WEB_ADMIN|ANDROID|IOS") String channel,String refreshToken) {}
    public record PasswordChangeRequest(@NotBlank @Size(max=128) String currentPassword,@NotBlank @Size(min=8,max=128) String newPassword) {}
    public record AddressRequest(@NotBlank @Pattern(regexp="\\d{12}") String provinceCode,@NotBlank @Pattern(regexp="\\d{12}") String cityCode,@NotBlank @Pattern(regexp="\\d{12}") String districtCode,@NotBlank @Size(min=2,max=200) String detail) {}
    public record ProfileCompletionRequest(@NotBlank @Size(min=18,max=18)String idCardNumber,@NotBlank @Pattern(regexp="1[3-9]\\d{9}")String mobileNumber,@NotBlank @Size(max=20)String ethnicity,@NotNull @Valid AddressRequest currentAddress,@NotNull @Valid AddressRequest householdAddress) {}
    public record SensitiveFieldRevealRequest(@NotBlank @Size(max=128)String password) {}
    public record ContextRequest(@NotBlank @Pattern(regexp="USER|ADMIN|SYSTEM_ADMIN") String workspace) {}
    public record AccountCreateRequest(@NotBlank @Size(max=100) String loginName,@NotBlank @Size(max=100) String displayName,@NotBlank @Size(max=100) String personNo,@Pattern(regexp="MALE|FEMALE|OTHER") String gender,UUID classId,@NotEmpty @Valid List<InitialRoleRequest> roleAssignments,@NotBlank @Size(min=2,max=500) String reason) {}
    public record AccountUpdateRequest(@NotBlank @Size(max=100) String loginName,@NotBlank @Size(max=100) String displayName,@Pattern(regexp="MALE|FEMALE|OTHER") String gender,@Size(max=20)String ethnicity,@Size(min=18,max=18)String idCardNumber,@Pattern(regexp="1[3-9]\\d{9}")String mobileNumber,@Valid AddressRequest currentAddress,@Valid AddressRequest householdAddress,@Min(1) long expectedVersion,@NotBlank @Size(min=2,max=500) String reason) {}
    public record EnrollmentChangeRequest(@NotNull UUID classId,@Min(1) long expectedVersion,@NotBlank @Size(min=2,max=500) String reason) {}
    public record InitialRoleRequest(@NotBlank @Pattern(regexp="USER|TEACHER_ADMIN|STUDENT_ADMIN|MEDICAL_INSURANCE_ADMIN") String roleCode,@NotNull @Valid List<ScopeCeilingRequest> scopeCeilings) {}
    public record ScopeCeilingRequest(@NotNull UUID orgUnitId) {}
    public record BulkCommandRequest(@NotEmpty @Size(max=5000) List<UUID> accountIds,@Min(1) @Max(5000) int expectedCount,@NotBlank @Size(min=2,max=500) String reason) {}
    public record ReasonRequest(@NotBlank @Size(min=2,max=500) String reason) {}
    public record ImportCommitRequest(@Min(1) long validatedRevision,@Min(1) @Max(5000) int expectedAccountCount,@NotBlank @Size(min=2,max=500) String reason) {}
    public record RoleAssignmentRequest(@NotBlank @Pattern(regexp="USER|TEACHER_ADMIN|STUDENT_ADMIN|MEDICAL_INSURANCE_ADMIN|SYSTEM_ADMIN") String roleCode,@NotNull List<UUID> scopeCeilingOrgUnitIds,@NotNull Instant validFrom,Instant validTo,@NotBlank @Size(min=2,max=500) String reason) {}
    public record ControlDecisionRequest(@Min(1) long expectedVersion,@NotBlank @Size(min=2,max=500) String reason) {}
    public record OrgWriteRequest(@NotBlank @Pattern(regexp="SCHOOL|COLLEGE|GRADE|CLASS") String type,UUID parentId,@NotBlank @Size(max=100) String code,@NotBlank @Size(max=200) String name,@Min(0) long expectedVersion,@NotBlank @Size(min=2,max=500) String reason) {}
    public record AccountExportRequest(@NotNull @Valid AccountExportSelection selection,@NotBlank @Size(min=2,max=500) String purpose) {}
    public record AccountExportSelection(@NotBlank @Pattern(regexp="SELECTED|FILTERED_ALL") String mode,@Size(max=5000) List<UUID> accountIds,@Valid AccountExportFilter filter,@Min(1) Integer expectedCount) {}
    public record AccountExportFilter(@Size(max=100) String q,@Size(max=3) List<@Pattern(regexp="ACTIVE|GRADUATED|DELETED") String> lifecycleStatus,@Size(max=5) List<@Pattern(regexp="USER|TEACHER_ADMIN|STUDENT_ADMIN|MEDICAL_INSURANCE_ADMIN|SYSTEM_ADMIN") String> roleCodes,@Size(max=5000) List<UUID> orgUnitIds) {}
    public record SsoExchangeRequest(@NotBlank @Size(max=200) String ticket,@NotBlank @Pattern(regexp="medical-insurance") String audience) {}
    public record SsoIntrospectRequest(@NotBlank @Size(max=512) String sessionToken) {}
    public record AuditExportRequest(@NotNull Instant occurredFrom,@NotNull Instant occurredTo,List<String> eventCodes,UUID actorAccountId,UUID subjectAccountId,@NotBlank @Size(min=2,max=500) String purpose) {}

    private static List<Map<String,Object>> initialRoleMaps(List<InitialRoleRequest> values){return values.stream().map(value->Map.<String,Object>of("roleCode",value.roleCode(),"scopeCeilings",value.scopeCeilings().stream().map(scope->Map.<String,Object>of("orgUnitId",scope.orgUnitId())).toList())).toList();}
    private static Map<String,String> addressMap(AddressRequest value){return value==null?null:Map.of("provinceCode",value.provinceCode(),"cityCode",value.cityCode(),"districtCode",value.districtCode(),"detail",value.detail());}
    private static Map<String,Object> exportMap(AccountExportRequest body){Map<String,Object> selection=new LinkedHashMap<>();selection.put("mode",body.selection().mode());if(body.selection().accountIds()!=null)selection.put("accountIds",body.selection().accountIds());if(body.selection().filter()!=null){var filter=body.selection().filter();Map<String,Object> values=new LinkedHashMap<>();put(values,"q",filter.q());put(values,"lifecycleStatus",filter.lifecycleStatus());put(values,"roleCodes",filter.roleCodes());put(values,"orgUnitIds",filter.orgUnitIds());selection.put("filter",values);}if(body.selection().expectedCount()!=null)selection.put("expectedCount",body.selection().expectedCount());return Map.of("selection",selection,"purpose",body.purpose());}
    private static Map<String,Object> auditExportMap(AuditExportRequest body){Map<String,Object> values=new LinkedHashMap<>();values.put("occurredFrom",body.occurredFrom().toString());values.put("occurredTo",body.occurredTo().toString());put(values,"eventCodes",body.eventCodes());put(values,"actorAccountId",body.actorAccountId());put(values,"subjectAccountId",body.subjectAccountId());values.put("purpose",body.purpose());return values;}
    private static <T> void put(Map<String,T> target,String name,T value){if(value!=null)target.put(name,value);}
}
