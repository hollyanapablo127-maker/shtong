import Combine
import Foundation

@MainActor
final class AppModel: ObservableObject {
    @Published var phase: AppPhase = .checking
    @Published var route: AppRoute = .home
    @Published var busy = false
    @Published var error: String?
    @Published var notice: String?
    @Published var bootstrap: Bootstrap?
    @Published var profile: Profile?
    @Published var accounts: [AccountSummary] = []
    @Published var nextCursor: String?
    @Published var selectedAccountIds: Set<String> = []
    @Published var accountDetail: AccountDetail?
    @Published var accountOptions: [AccountSummary] = []
    @Published var accountOptionsNext: String?
    @Published var assignments: [RoleAssignment] = []
    @Published var orgUnits: [OrgUnit] = []
    @Published var audits: [AuditEvent] = []
    @Published var auditDetail: AuditEventDetail?
    @Published var controls: [ControlRequest] = []
    @Published var importJob: ImportJob?
    @Published var importIssues: [ImportIssue] = []
    @Published var exportData: Data?
    @Published var exportFileName = "shtong-export.csv"
    @Published var exportIsSpreadsheet = false
    @Published var pendingCredentialCommandId: String?
    @Published var pendingImportCredentialJobId: String?
    @Published var pendingAccountExportJobId: String?
    @Published var pendingAuditExportJobId: String?
    @Published var provinces: [AdministrativeDivision] = []
    @Published var currentCities: [AdministrativeDivision] = []
    @Published var currentDistricts: [AdministrativeDivision] = []
    @Published var householdCities: [AdministrativeDivision] = []
    @Published var householdDistricts: [AdministrativeDivision] = []
    @Published var medicalInsuranceURL: URL?

    private let keychain: KeychainStore
    private let api: APIClient
    private var currentAccountQuery = ""
    private var pendingRoute: AppRoute?

    init(keychain: KeychainStore = KeychainStore(), apiBaseURL: URL? = nil) {
        self.keychain = keychain
        self.api = APIClient(keychain: keychain, baseURL: apiBaseURL)
        restore()
    }

    var isSystemWorkspace: Bool { bootstrap?.activeContext?.workspace == "SYSTEM_ADMIN" }
    var routes: [AppRoute] {
        guard isSystemWorkspace else {
            let medical = (bootstrap?.activeModules ?? []).contains { $0.code == "medical-insurance" }
            return [.home] + (medical ? [.medicalInsurance] : []) + [.profile]
        }
        let activeModules = bootstrap?.activeModules ?? []
        var active = activeModules.sorted { $0.order < $1.order }.compactMap { route(forModule: $0.code) }
        // Keep the module visible for a system administrator even if an older
        // bootstrap payload omitted the module from its route projection.
        if activeModules.contains(where: { $0.code == "medical-insurance" }) && !active.contains(.medicalInsurance) {
            active.append(.medicalInsurance)
        }
        return [.home] + active + [.profile]
    }

    func restore() {
        guard keychain.access() != nil else {
            phase = .login
            return
        }
        perform { try await self.loadBootstrap() }
    }

    func login(loginName: String, password: String) {
        guard !loginName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty else {
            error = "请输入登录名和密码"
            return
        }
        perform {
            let response: LoginResponse = try await self.api.send("auth/login", body: LoginRequest(loginName: loginName, password: password))
            try self.applyLogin(response)
            if response.status == "AUTHENTICATED" || response.status == "PROFILE_COMPLETION_REQUIRED" { try await self.loadBootstrap() }
        }
    }

    func switchWorkspace(_ workspace: String) {
        perform {
            _ = try await self.api.switchContext(workspace: workspace)
            try await self.loadBootstrap()
        }
    }

    func changePassword(current: String, new: String, confirmation: String) {
        guard new == confirmation else {
            error = "两次输入的新密码不一致"
            return
        }
        guard (8...128).contains(new.count) else {
            error = "新密码长度需为 8 至 128 个字符"
            return
        }
        guard Self.meetsPasswordPolicy(new) else {
            error = "密码至少 8 位，并同时包含大写字母、小写字母和数字"
            return
        }
        perform {
            let session: SessionEstablished = try await self.api.send("auth/password/change", body: PasswordChangeRequest(currentPassword: current, newPassword: new))
            try self.store(session.mobileTokens)
            try await self.loadBootstrap()
            self.notice = "密码已更新，其他旧会话已撤销"
        }
    }

    func logout() {
        Task {
            busy = true
            var remoteFailure = false
            do {
                let _: EmptyResponse = try await api.send("auth/logout")
            } catch {
                remoteFailure = true
            }
            clearProtectedState()
            phase = .login
            busy = false
            error = remoteFailure ? "本机已退出，服务端会话撤销待网络恢复后确认" : nil
        }
    }

    @Published var accountOrgFilter = ""
    private var accountLifecycle = "ACTIVE"
    private var accountScopeQuery: [URLQueryItem] {
        var result = [URLQueryItem(name: "lifecycleStatus", value: accountLifecycle)]
        if !accountOrgFilter.isEmpty { result.append(URLQueryItem(name: "orgUnitId", value: accountOrgFilter)) }
        return result
    }

    func navigate(to route: AppRoute) {
        guard routes.contains(route) else { return }
        self.route = route
        error = nil
        notice = nil
        switch route {
        case .accounts, .graduates:
            accountLifecycle = route == .graduates ? "GRADUATED" : "ACTIVE"
            accountOrgFilter = ""
            accounts = []
            selectedAccountIds.removeAll()
            loadAccounts(query: "")
        case .authorization: break
        case .organization: loadOrganizations()
        case .audit: loadAudit(eventCode: "")
        case .profile: loadProfile()
        case .medicalInsurance: break
        case .home: break
        }
    }

    func openMedicalInsurance() {
        perform {
            let ticket = try await self.api.issueMedicalInsuranceTicket()
            guard let url = URL(string: ticket.redirectUrl) else { throw URLError(.badURL) }
            self.medicalInsuranceURL = url
            self.route = .medicalInsurance
        }
    }

    func closeMedicalInsurance() {
        medicalInsuranceURL = nil
        route = .home
    }

    func handleDeepLink(_ url: URL) {
        let requested = AppRoute(rawValue: url.lastPathComponent)
        guard phase == .workspace else {
            pendingRoute = requested
            restore()
            return
        }
        guard let requested, routes.contains(requested) else { return }
        navigate(to: requested)
    }

    func loadProfile() {
        perform {
            let value: Profile = try await self.api.get("profile")
            self.profile = value
        }
    }

    func loadProfileCompletionOptions() {
        perform {
            if self.profile == nil { self.profile = try await self.api.get("profile") }
            if self.provinces.isEmpty {
                self.provinces = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "PROVINCE")])
            }
        }
    }

    func selectProfileProvince(kind: String, code: String) {
        perform {
            let values: [AdministrativeDivision] = code.isEmpty ? [] : try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "CITY"), URLQueryItem(name: "parentCode", value: code)])
            if kind == "current" { self.currentCities = values; self.currentDistricts = [] }
            else { self.householdCities = values; self.householdDistricts = [] }
        }
    }

    func selectProfileCity(kind: String, code: String) {
        perform {
            let values: [AdministrativeDivision] = code.isEmpty ? [] : try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "DISTRICT"), URLQueryItem(name: "parentCode", value: code)])
            if kind == "current" { self.currentDistricts = values }
            else { self.householdDistricts = values }
        }
    }

    func completeProfile(idCardNumber: String, mobileNumber: String, ethnicity: String, currentAddress: ProfileAddressInput, householdAddress: ProfileAddressInput) {
        perform {
            let body = ProfileCompletionRequest(idCardNumber: idCardNumber.uppercased(), mobileNumber: mobileNumber, ethnicity: ethnicity, currentAddress: currentAddress, householdAddress: householdAddress)
            let response: ProfileCompletionResponse = try await self.api.send("profile/completion", body: body)
            try self.store(response.session.mobileTokens)
            self.profile = response.profile
            try await self.loadBootstrap()
            self.notice = "信息已完善"
        }
    }

    func revealSensitiveProfileField(_ field: String, password: String, onSuccess: @escaping (String) -> Void) {
        perform {
            let response: SensitiveProfileFieldResponse = try await self.api.send("profile/sensitive-fields/\(field)/reveal", body: SensitiveFieldRevealRequest(password: password))
            onSuccess(response.value)
            self.notice = "身份已验证，资料已复制"
        }
    }

    func changeProfilePassword(current: String, new: String, confirmation: String, onSuccess: @escaping () -> Void = {}) {
        guard new == confirmation else { error = "两次输入的新密码不一致"; return }
        guard (8...128).contains(new.count) else { error = "新密码长度需为 8 至 128 个字符"; return }
        perform {
            let session: SessionEstablished = try await self.api.send("auth/password/change", body: PasswordChangeRequest(currentPassword: current, newPassword: new))
            try self.store(session.mobileTokens)
            try await self.loadBootstrap()
            self.route = .profile
            self.notice = "密码已修改，其他旧会话已退出"
            onSuccess()
        }
    }

    func loadAccounts(cursor: String? = nil, query: String) {
        currentAccountQuery = query
        if cursor == nil { selectedAccountIds.removeAll() }
        perform {
            if self.orgUnits.isEmpty { self.orgUnits = try await self.api.get("system/org-units") }
            var items = [URLQueryItem(name: "limit", value: "50")] + self.accountScopeQuery
            if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
            if !query.isEmpty { items.append(URLQueryItem(name: "q", value: query)) }
            let page: AccountPage = try await self.api.get("system/accounts", query: items)
            self.accounts = cursor == nil ? page.items : self.accounts + page.items
            self.nextCursor = page.nextCursor
        }
    }

    func loadAccountDetail(id: String) {
        perform {
            if self.orgUnits.isEmpty { self.orgUnits = try await self.api.get("system/org-units") }
            let value: AccountDetail = try await self.api.get("system/accounts/\(id)")
            self.accountDetail = value
        }
    }

    func updateAccount(loginName: String, displayName: String, gender: String?, reason: String, idCardNumber: String? = nil, mobileNumber: String? = nil, currentAddress: ProfileAddressInput? = nil, householdAddress: ProfileAddressInput? = nil, onSuccess: @escaping () -> Void = {}) {
        guard let detail = accountDetail else { return }
        perform {
            let body = AccountUpdateRequest(loginName: loginName, displayName: displayName, gender: gender?.nilIfBlank, idCardNumber: detail.profileCompleted ? idCardNumber : nil, mobileNumber: detail.profileCompleted ? mobileNumber : nil, currentAddress: detail.profileCompleted ? currentAddress : nil, householdAddress: detail.profileCompleted ? householdAddress : nil, expectedVersion: detail.version, reason: reason)
            let value: AccountDetail = try await self.api.send("system/accounts/\(detail.id)", method: "PATCH", body: body)
            self.accountDetail = value
            self.accounts = self.accounts.map { $0.id == value.id ? value.summary : $0 }
            self.notice = "账户资料已更正"
            onSuccess()
        }
    }

    func loadAccountAddressOptions(_ detail: AccountDetail) {
        perform {
            self.provinces = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "PROVINCE")])
            if let current = detail.currentAddress {
                self.currentCities = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "CITY"), URLQueryItem(name: "parentCode", value: current.provinceCode)])
                self.currentDistricts = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "DISTRICT"), URLQueryItem(name: "parentCode", value: current.cityCode)])
            }
            if let household = detail.householdAddress {
                self.householdCities = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "CITY"), URLQueryItem(name: "parentCode", value: household.provinceCode)])
                self.householdDistricts = try await self.api.get("reference/administrative-divisions", query: [URLQueryItem(name: "level", value: "DISTRICT"), URLQueryItem(name: "parentCode", value: household.cityCode)])
            }
        }
    }

    func changeEnrollment(classId: String, reason: String, onSuccess: @escaping () -> Void = {}) {
        guard let detail = accountDetail else { return }
        perform {
            let value: AccountDetail = try await self.api.send("system/accounts/\(detail.id)/enrollment-change", body: EnrollmentChangeRequest(classId: classId, expectedVersion: detail.version, reason: reason))
            self.accountDetail = value
            self.accounts = self.accounts.map { $0.id == value.id ? value.summary : $0 }
            self.notice = "学籍变动已完成，该学生需要重新登录"
            onSuccess()
        }
    }

    func toggleAccount(_ id: String) {
        if selectedAccountIds.contains(id) { selectedAccountIds.remove(id) }
        else { selectedAccountIds.insert(id) }
    }

    func clearSelection() { selectedAccountIds.removeAll() }

    func selectAllAccounts(query: String) {
        currentAccountQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        perform {
            var all: [AccountSummary] = []
            var cursor: String?
            repeat {
                var items = [URLQueryItem(name: "limit", value: "200")] + self.accountScopeQuery
                if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
                if !self.currentAccountQuery.isEmpty { items.append(URLQueryItem(name: "q", value: self.currentAccountQuery)) }
                let page: AccountPage = try await self.api.get("system/accounts", query: items)
                all.append(contentsOf: page.items)
                if all.count > 5_000 {
                    self.error = "筛选结果超过 5000 个账户，请增加筛选条件后再全选"
                    return
                }
                cursor = page.nextCursor
            } while cursor != nil
            self.accounts = all
            self.nextCursor = nil
            self.selectedAccountIds = Set(all.map(\.id))
            self.notice = self.currentAccountQuery.isEmpty ? "已选择全部账户，共 \(all.count) 个" : "已选择筛选后的全部账户，共 \(all.count) 个"
        }
    }

    func createAccount(loginName: String, displayName: String, personNo: String, gender: String?, classId: String?, roleCode: String, scopeIds: [String], reason: String, onSuccess: @escaping () -> Void = {}) {
        let body = AccountCreateRequest(
            loginName: loginName,
            displayName: displayName,
            personNo: personNo,
            gender: roleCode == "USER" ? gender?.nilIfBlank : nil,
            classId: classId?.nilIfBlank,
            roleAssignments: [InitialRoleRequest(roleCode: roleCode, scopeCeilings: scopeIds.map { ScopeCeilingRequest(orgUnitId: $0) })],
            reason: reason
        )
        perform {
            let result: AccountCreateResponse = try await self.api.send("system/accounts", body: body)
            self.notice = result.credential.map { "临时密码仅显示一次：\($0.temporaryPassword)" } ?? "账户已创建"
            onSuccess()
            try await self.reloadAccounts()
        }
    }

    func runAccountCommand(_ command: String, reason: String) {
        guard !selectedAccountIds.isEmpty else {
            error = "请先选择账户"
            return
        }
        let ids = Array(selectedAccountIds).sorted()
        perform {
            let result: JobAccepted = try await self.api.send("system/accounts/commands/\(command)", body: BulkCommandRequest(accountIds: ids, expectedCount: ids.count, reason: reason))
            self.selectedAccountIds.removeAll()
            if command == "reset-password" { self.pendingCredentialCommandId = result.jobId }
            self.notice = result.controlRequestId.map { _ in "等待另一位管理员在安全复核中确认" } ?? "账户操作已完成"

            try await self.reloadAccounts()
        }
    }

    func refreshCommandCredential() {
        guard let id = pendingCredentialCommandId else { return }
        perform {
            let status: JobStatus = try await self.api.get("system/account-commands/\(id)")
            guard status.state == "COMPLETED" else { self.notice = "操作状态：\(UiText.label(status.state))"; return }
            try await self.downloadCommandCredential(id: id)
        }
    }

    func uploadImport(data: Data, fileName: String) {
        perform {
            let accepted = try await self.api.upload("system/account-import/jobs", data: data, fileName: fileName)
            let job: ImportJob = try await self.api.get("system/account-import/jobs/\(accepted.jobId)")
            let issues: Page<ImportIssue> = try await self.api.get("system/account-import/jobs/\(accepted.jobId)/errors")
            self.importJob = job
            self.importIssues = issues.items
            self.notice = "名单检查完成：\(UiText.label(job.state))"
        }
    }

    func downloadImportTemplate() {
        perform {
            self.exportData = try await self.api.download("system/account-import/template")
            self.exportFileName = "生化通账户导入模板.xlsx"
            self.exportIsSpreadsheet = true
            self.notice = "官方导入模板已就绪"
        }
    }

    func commitImport(reason: String) {
        guard let job = importJob, job.state == "VALID" else { return }
        perform {
            let _: JobAccepted = try await self.api.send("system/account-import/jobs/\(job.id)/commit", body: ImportCommitRequest(validatedRevision: job.organizationRevision, expectedAccountCount: job.rowCount, reason: reason))
            self.importJob = ImportJob(id: job.id, state: "COMMITTED", templateVersion: job.templateVersion, organizationRevision: job.organizationRevision, rowCount: job.rowCount, errorCount: 0)
            self.pendingImportCredentialJobId = job.id
            self.notice = "导入完成，请领取初始密码"
            try await self.reloadAccounts()
        }
    }

    func downloadImportCredentials() {
        guard let id = pendingImportCredentialJobId else { return }
        perform {
            let ticket: DownloadTicket = try await self.api.send("system/account-import/jobs/\(id)/credential-ticket")
            self.exportData = try await self.api.download(ticket.url)
            self.exportFileName = "导入账户的初始密码.csv"
            self.exportIsSpreadsheet = false
            self.importJob = nil
            self.importIssues = []
            self.pendingImportCredentialJobId = nil
            self.notice = "初始密码文件已就绪，请保存"
        }
    }

    func clearImport() {
        importJob = nil
        importIssues = []
    }

    func requestAccountExport(reason: String) {
        perform {
            let count = self.selectedAccountIds.isEmpty ? try await self.countAllAccounts() : self.selectedAccountIds.count
            guard count > 0 else { self.notice = "当前筛选结果为空，无可导出账户"; return }
            let selection: JSONObject
            if self.selectedAccountIds.isEmpty {
                var filter: JSONObject = ["lifecycleStatus": .array([.string(self.accountLifecycle)])]
                if !self.accountOrgFilter.isEmpty { filter["orgUnitIds"] = .array([.string(self.accountOrgFilter)]) }
                if !self.currentAccountQuery.isEmpty { filter["q"] = .string(self.currentAccountQuery) }
                selection = ["mode": .string("FILTERED_ALL"), "filter": .object(filter), "expectedCount": .number(Double(count))]
            } else {
                selection = ["mode": .string("SELECTED"), "accountIds": .array(self.selectedAccountIds.sorted().map { .string($0) })]
            }
            let body: JSONObject = ["selection": .object(selection), "purpose": .string(reason)]
            let job: JobAccepted = try await self.api.send("system/account-export/jobs", body: body)
            self.pendingAccountExportJobId = job.jobId
            try await self.downloadAccountExportIfReady(id: job.jobId, fallbackCount: count)
        }
    }

    func refreshAccountExport() {
        guard let id = pendingAccountExportJobId else { return }
        perform { try await self.downloadAccountExportIfReady(id: id, fallbackCount: 0) }
    }

    func clearExport() { exportData = nil }

    func loadEditorData() {
        perform {
            try await self.reloadOrganizations()
            let page: AccountPage = try await self.api.get("system/accounts", query: [URLQueryItem(name: "limit", value: "30")])
            self.accountOptions = page.items
            self.accountOptionsNext = page.nextCursor
        }
    }

    func searchAccountOptions(query: String, more: Bool = false) {
        perform {
            var items = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "limit", value: "30")]
            if more, let cursor = self.accountOptionsNext { items.append(URLQueryItem(name: "cursor", value: cursor)) }
            let page: AccountPage = try await self.api.get("system/accounts", query: items)
            self.accountOptions = more ? self.accountOptions + page.items : page.items
            self.accountOptionsNext = page.nextCursor
        }
    }

    func loadAssignments(accountId: String) {
        guard !accountId.isEmpty else { return }
        perform {
            let values: [RoleAssignment] = try await self.api.get("system/accounts/\(accountId)/role-assignments")
            self.assignments = values
        }
    }

    func addAssignment(accountId: String, roleCode: String, scopeIds: [String], reason: String) {
        let request = RoleAssignmentRequest(roleCode: roleCode, scopeCeilingOrgUnitIds: scopeIds, validFrom: ISO8601DateFormatter().string(from: Date()), validTo: nil, reason: reason)
        perform {
            let _: JSONObject = try await self.api.send("system/accounts/\(accountId)/role-assignments", body: request)
            self.notice = "授权请求已提交"
            let values: [RoleAssignment] = try await self.api.get("system/accounts/\(accountId)/role-assignments")
            self.assignments = values
        }
    }

    func revokeAssignment(id: String, reason: String, accountId: String = "") {
        perform {
            let _: JSONObject = try await self.api.send("system/role-assignments/\(id)/revoke", body: ReasonRequest(reason: reason))
            self.notice = "取消申请已提交"
            if !accountId.isEmpty { self.assignments = try await self.api.get("system/accounts/\(accountId)/role-assignments") }
        }
    }

    func loadControls() {
        perform {
            let page: Page<ControlRequest> = try await self.api.get("system/control-requests")
            self.controls = page.items
        }
    }

    func decideControl(_ item: ControlRequest, approve: Bool, reason: String) {
        perform {
            let action = approve ? "approve" : "reject"
            let _: JSONObject = try await self.api.send("system/control-requests/\(item.id)/\(action)", body: ControlDecisionRequest(expectedVersion: item.version, reason: reason))
            self.notice = "复核决定已提交"
            let page: Page<ControlRequest> = try await self.api.get("system/control-requests")
            self.controls = page.items
        }
    }

    func loadOrganizations() {
        perform {
            let values: [OrgUnit] = try await self.api.get("system/org-units")
            self.orgUnits = values
        }
    }

    func createOrganization(type: String, parentId: String?, code: String, name: String, reason: String, onSuccess: @escaping () -> Void = {}) {
        let body = OrgWriteRequest(type: type, parentId: type == "SCHOOL" ? nil : parentId?.nilIfBlank, code: code.isEmpty ? "ORG-" + String(UUID().uuidString.prefix(8)) : code, name: name, expectedVersion: 0, reason: reason)
        perform {
            let _: OrgUnit = try await self.api.send("system/org-units", body: body)
            self.notice = "已创建"
            onSuccess()
            try await self.reloadOrganizations()
        }
    }

    func updateOrganization(_ unit: OrgUnit, name: String, reason: String, onSuccess: @escaping () -> Void = {}) {
        let body = OrgWriteRequest(type: unit.type, parentId: unit.parentId, code: unit.code, name: name, expectedVersion: unit.version, reason: reason)
        perform {
            let _: OrgUnit = try await self.api.send("system/org-units/\(unit.id)", method: "PATCH", body: body)
            self.notice = "名称已保存"
            onSuccess()
            try await self.reloadOrganizations()
        }
    }

    func deleteOrganization(_ unit: OrgUnit, reason: String) {
        perform {
            let _: EmptyResponse = try await self.api.send("system/org-units/\(unit.id)", method: "DELETE", body: ReasonRequest(reason: reason))
            self.notice = "组织已删除，可重新使用原名称和编号创建"
            try await self.reloadOrganizations()
        }
    }

    func loadAudit(eventCode: String, actorAccountId: String = "", subjectAccountId: String = "", requestId: String = "") {
        perform {
            let now = Date()
            var query = [
                URLQueryItem(name: "occurredFrom", value: ISO8601DateFormatter().string(from: now.addingTimeInterval(-31 * 86_400))),
                URLQueryItem(name: "occurredTo", value: ISO8601DateFormatter().string(from: now))
            ]
            if !eventCode.isEmpty { query.append(URLQueryItem(name: "eventCode", value: eventCode)) }
            if !actorAccountId.isEmpty { query.append(URLQueryItem(name: "actorAccountId", value: actorAccountId)) }
            if !subjectAccountId.isEmpty { query.append(URLQueryItem(name: "subjectAccountId", value: subjectAccountId)) }
            if !requestId.isEmpty { query.append(URLQueryItem(name: "requestId", value: requestId)) }
            let page: Page<AuditEvent> = try await self.api.get("system/audit/events", query: query)
            self.audits = page.items
        }
    }

    func loadAuditDetail(id: String) {
        perform {
            let detail: AuditEventDetail = try await self.api.get("system/audit/events/\(id)")
            self.auditDetail = detail
        }
    }

    func requestAuditExport(reason: String, eventCode: String = "", actorAccountId: String = "", subjectAccountId: String = "") {
        perform {
            let now = Date()
            var body: JSONObject = [
                "occurredFrom": .string(ISO8601DateFormatter().string(from: now.addingTimeInterval(-31 * 86_400))),
                "occurredTo": .string(ISO8601DateFormatter().string(from: now)),
                "purpose": .string(reason)
            ]
            if !eventCode.isEmpty { body["eventCodes"] = .array([.string(eventCode)]) }
            if !actorAccountId.isEmpty { body["actorAccountId"] = .string(actorAccountId) }
            if !subjectAccountId.isEmpty { body["subjectAccountId"] = .string(subjectAccountId) }
            let job: JobAccepted = try await self.api.send("system/audit/export-jobs", body: body)
            self.pendingAuditExportJobId = job.jobId
            self.notice = job.controlRequestId.map { _ in "导出申请等待另一位管理员确认" } ?? "审计导出任务已创建"
        }
    }

    func refreshAuditExport() {
        guard let id = pendingAuditExportJobId else { return }
        perform {
            let status: JobStatus = try await self.api.get("system/audit/export-jobs/\(id)")
            guard status.state == "READY" else { self.notice = "审计导出状态：\(UiText.label(status.state))"; return }
            let ticket: DownloadTicket = try await self.api.send("system/audit/export-jobs/\(id)/download-ticket")
            self.exportData = try await self.api.download(ticket.url)
            self.exportFileName = "操作记录.csv"
            self.exportIsSpreadsheet = false
            self.pendingAuditExportJobId = nil
            self.notice = "审计导出已就绪，共 \(status.resultCount ?? 0) 条"
        }
    }

    private func perform(_ operation: @escaping @MainActor () async throws -> Void) {
        Task {
            busy = true
            error = nil
            notice = nil
            defer { busy = false }
            do { try await operation() }
            catch is SessionInvalidatedError {
                clearProtectedState()
                phase = .login
                error = "会话已失效，请重新登录"
            } catch let transport as APITransportError {
                if transport.status == 401 || transport.body.code == "AUTHORIZATION_VERSION_STALE" {
                    clearProtectedState()
                    phase = .login
                    error = "会话已失效，请重新登录"
                } else {
                    error = message(for: transport.body.code, requestId: transport.body.requestId)
                }
            } catch let body as APIErrorBody {
                self.error = message(for: body.code, requestId: body.requestId)
            } catch {
                self.error = "网络连接失败，请稍后重试"
            }
        }
    }

    /// Same rule as the server: 8 to 128 characters with an ASCII uppercase letter, lowercase letter and digit.
    static func meetsPasswordPolicy(_ value: String) -> Bool {
        (8...128).contains(value.count)
            && value.contains { ("A"..."Z").contains($0) }
            && value.contains { ("a"..."z").contains($0) }
            && value.contains { ("0"..."9").contains($0) }
    }

    private func applyLogin(_ response: LoginResponse) throws {
        switch response.status {
        case "PASSWORD_CHANGE_REQUIRED":
            try store(response.session?.mobileTokens)
            phase = .passwordChange
        case "PROFILE_COMPLETION_REQUIRED":
            try store(response.session?.mobileTokens)
        case "AUTHENTICATED":
            try store(response.session?.mobileTokens)
        default:
            throw APIErrorBody(code: "INVALID_CREDENTIALS", messageKey: nil, requestId: nil)
        }
    }

    private func store(_ tokens: MobileTokens?) throws {
        guard let tokens else { throw APIErrorBody(code: "TOKEN_DELIVERY_FAILED", messageKey: nil, requestId: nil) }
        try keychain.save(access: tokens.accessToken, refresh: tokens.refreshToken)
    }

    private func loadBootstrap() async throws {
        let value: Bootstrap = try await api.get("session/bootstrap")
        guard value.contractVersion == Contract.version else {
            throw APIErrorBody(code: "CONTRACT_VERSION_MISMATCH", messageKey: nil, requestId: nil)
        }
        bootstrap = value
        if value.profileCompletionRequired {
            profile = try await api.get("profile")
            phase = .profileCompletion
            pendingRoute = nil
            return
        }
        phase = .workspace
        let initial: AppRoute = .home
        route = pendingRoute.flatMap { routes.contains($0) ? $0 : nil } ?? initial
        pendingRoute = nil
        navigate(to: route)
    }

    private func reloadAccounts() async throws {
        var query = [URLQueryItem(name: "limit", value: "50")] + accountScopeQuery
        if !currentAccountQuery.isEmpty { query.append(URLQueryItem(name: "q", value: currentAccountQuery)) }
        let page: AccountPage = try await api.get("system/accounts", query: query)
        accounts = page.items
        nextCursor = page.nextCursor
    }

    private func countAllAccounts() async throws -> Int {
        var count = 0
        var cursor: String?
        repeat {
            var query = [URLQueryItem(name: "limit", value: "200")] + accountScopeQuery
            if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor)) }
            if !currentAccountQuery.isEmpty { query.append(URLQueryItem(name: "q", value: currentAccountQuery)) }
            let page: AccountPage = try await api.get("system/accounts", query: query)
            count += page.items.count
            cursor = page.nextCursor
        } while cursor != nil
        return count
    }

    private func downloadCommandCredential(id: String) async throws {
        let ticket: DownloadTicket = try await api.send("system/account-commands/\(id)/credential-ticket")
        exportData = try await api.download(ticket.url)
        exportFileName = "重置后的初始密码.csv"
        exportIsSpreadsheet = false
        pendingCredentialCommandId = nil
        notice = "初始密码文件已就绪"
    }

    private func downloadAccountExportIfReady(id: String, fallbackCount: Int) async throws {
        let status: JobStatus = try await api.get("system/account-export/jobs/\(id)")
        guard status.state == "READY" else { notice = "账户导出状态：\(UiText.label(status.state))"; return }
        let ticket: DownloadTicket = try await api.send("system/account-export/jobs/\(id)/download-ticket")
        exportData = try await api.download(ticket.url)
        exportFileName = "账户名单.csv"
        exportIsSpreadsheet = false
        pendingAccountExportJobId = nil
        notice = "导出文件已就绪，共 \(status.resultCount ?? fallbackCount) 条"
    }

    private func reloadOrganizations() async throws {
        let values: [OrgUnit] = try await api.get("system/org-units")
        orgUnits = values
    }

    private func route(forModule code: String) -> AppRoute? {
        switch code {
        case "system.accounts": .accounts
        case "system.graduates": .graduates
        case "system.authorization": .authorization
        case "system.organization": .organization
        case "system.audit": .audit
        case "medical-insurance": .medicalInsurance
        default: nil
        }
    }

    private func clearProtectedState() {
        keychain.clear()
        bootstrap = nil
        profile = nil
        accounts = []
        nextCursor = nil
        selectedAccountIds = []
        accountDetail = nil
        assignments = []
        accountOptions = []
        accountOptionsNext = nil
        orgUnits = []
        audits = []
        auditDetail = nil
        controls = []
        importJob = nil
        importIssues = []
        exportData = nil
        exportIsSpreadsheet = false
        pendingCredentialCommandId = nil
        pendingImportCredentialJobId = nil
        pendingAccountExportJobId = nil
        pendingAuditExportJobId = nil
        pendingRoute = nil
        currentAccountQuery = ""
        provinces = []
        currentCities = []
        currentDistricts = []
        householdCities = []
        householdDistricts = []
        route = .home
    }

    private func message(for code: String, requestId: String?) -> String {
        let text: String
        switch code {
        case "INVALID_CREDENTIALS", "AUTHENTICATION_FAILED": text = "账号或凭据无效"
        case "STEP_UP_REQUIRED": text = "需要重新完成二次认证"
        case "ACCESS_DENIED": text = "没有执行此操作的权限"
        case "EXPORT_SELECTION_CHANGED": text = "导出集合已变化，请刷新后确认"
        case "VERSION_CONFLICT": text = "数据已被其他管理员修改，请刷新后重试"
        case "CONTRACT_VERSION_MISMATCH": text = "客户端契约版本与服务端不一致"
        default: text = UiText.label(code) == code ? "操作未完成，请检查填写内容或稍后重试" : UiText.label(code)
        }
        return text
    }
}

private extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
