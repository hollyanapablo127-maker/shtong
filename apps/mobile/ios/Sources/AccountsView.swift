import SwiftUI
import UniformTypeIdentifiers

struct AccountsView: View {
    var graduates = false
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var reason = "账户维护"
    @State private var showCreate = false
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var pendingCommand: BulkAction?

    var body: some View {
        VStack(spacing: 0) {
            FeedbackBanner(error: model.error, notice: model.notice).padding([.horizontal, .top], 12)
            Picker("学院、年级或班级", selection: $model.accountOrgFilter) {
                Text("全部组织").tag("")
                ForEach(model.orgUnits) { unit in Text(organizationName(unit.id, units: model.orgUnits)).tag(unit.id) }
            }
            .pickerStyle(.menu)
            .padding(.horizontal, 12)
            .onChange(of: model.accountOrgFilter) { _, _ in model.loadAccounts(query: query) }
            if model.selectedAccountIds.isEmpty { TextField("导出用途", text: $reason).shtongField().padding(.horizontal, 12) }
            actionBar
            if !model.selectedAccountIds.isEmpty { selectionBar }
            List {
                ForEach(model.accounts) { account in accountRow(account) }
                if let cursor = model.nextCursor {
                    Button { model.loadAccounts(cursor: cursor, query: query) } label: {
                        HStack { Spacer(); Text("加载下一页"); Spacer() }.frame(minHeight: 44)
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if model.accounts.isEmpty && !model.busy {
                    ContentUnavailableView("没有账户", systemImage: "person.2.slash", description: Text("调整搜索条件后重试。"))
                }
            }
        }
        .disabled(model.busy)
        .background(ShtongColor.background)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "登录账号、姓名或学工号")
        .onSubmit(of: .search) { model.loadAccounts(query: query) }
        .onChange(of: query) { _, value in if value.isEmpty { model.loadAccounts(query: "") } }
        .sheet(isPresented: $showCreate) { AccountCreateSheet() }
        .sheet(item: $model.accountDetail) { detail in AccountDetailSheet(detail: detail) }
        .sheet(item: $model.importJob) { job in ImportReviewSheet(job: job) }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [UTType(filenameExtension: "xlsx") ?? .data]) { result in
            guard case .success(let url) = result else {
                if case .failure = result { model.error = "无法打开所选文件，请重新选择" }
                return
            }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url, options: .mappedIfSafe) {
                model.uploadImport(data: data, fileName: url.lastPathComponent)
            } else {
                model.error = "无法读取所选文件，请确认文件已下载到本机且可以打开"
            }
        }
        .fileExporter(
            isPresented: $showExporter,
            document: ExportDocument(data: model.exportData ?? Data()),
            contentType: model.exportIsSpreadsheet ? (UTType(filenameExtension: "xlsx") ?? .data) : .commaSeparatedText,
            defaultFilename: model.exportFileName
        ) { result in
            if case .success = result { model.clearExport() }
            else if case .failure(let error) = result { model.error = "文件未保存，请重试：" + error.localizedDescription }
        }
        .onChange(of: model.exportData) { _, value in if value != nil { showExporter = true } }
        .confirmationDialog(
            pendingCommand?.confirmationTitle ?? "确认账户操作",
            isPresented: Binding(get: { pendingCommand != nil }, set: { if !$0 { pendingCommand = nil } }),
            titleVisibility: .visible
        ) {
            if let pendingCommand {
                Button(pendingCommand.buttonTitle, role: pendingCommand == .delete ? .destructive : nil) {
                    model.runAccountCommand(pendingCommand.path, reason: reason)
                    self.pendingCommand = nil
                }
            }
            Button("取消", role: .cancel) { pendingCommand = nil }
        } message: {
            Text("将处理已选的 \(model.selectedAccountIds.count) 个账户。原因：\(reason)")
        }
    }

    private var actionBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if !graduates { Button { showCreate = true } label: { Label("新增", systemImage: "person.badge.plus") } }
                Button { model.selectAllAccounts(query: query) } label: {
                    Label(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "全选全部账户" : "全选筛选结果", systemImage: "checkmark.square")
                }
                if !graduates {
                    Button { model.downloadImportTemplate() } label: { Label("模板", systemImage: "doc.badge.arrow.up") }
                    Button { showImporter = true } label: { Label("导入", systemImage: "square.and.arrow.down") }
                }
                Button { model.requestAccountExport(reason: reason) } label: {
                    Label(model.selectedAccountIds.isEmpty ? "导出当前全部" : "导出已选", systemImage: "square.and.arrow.up")
                }
                if model.exportData != nil { Button("保存待下载文件") { showExporter = true } }
                if !graduates && model.pendingCredentialCommandId != nil {
                    Button { model.refreshCommandCredential() } label: { Label("领取初始密码", systemImage: "key.viewfinder") }
                }
                if !graduates && model.pendingImportCredentialJobId != nil {
                    Button("领取导入账户的初始密码") { model.downloadImportCredentials() }.disabled(model.busy)
                }
                if model.pendingAccountExportJobId != nil {
                    Button { model.refreshAccountExport() } label: { Label("刷新账户导出", systemImage: "arrow.clockwise") }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .padding(12)
        }
    }

    private var selectionBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("已选 \(model.selectedAccountIds.count) 项").font(.headline)
                Spacer()
                Button("清除选择", action: model.clearSelection)
            }
            TextField("操作原因", text: $reason).shtongField()
            if !graduates { ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button { pendingCommand = .reset } label: { Label("重置密码", systemImage: "key") }
                    Button { pendingCommand = .graduate } label: { Label("移入毕业生", systemImage: "graduationcap") }
                    Button(role: .destructive) { pendingCommand = .delete } label: { Label("删除账户", systemImage: "trash") }
                }
                .buttonStyle(.bordered)
            } }
        }
        .padding(12)
        .background(ShtongColor.muted)
    }

    private func accountRow(_ account: AccountSummary) -> some View {
        HStack(spacing: 10) {
            Button { model.toggleAccount(account.id) } label: {
                Image(systemName: model.selectedAccountIds.contains(account.id) ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundStyle(model.selectedAccountIds.contains(account.id) ? ShtongColor.primary : .secondary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.selectedAccountIds.contains(account.id) ? "取消选择 \(account.displayName)" : "选择 \(account.displayName)")

            VStack(alignment: .leading, spacing: 4) {
                Text(account.displayName).font(.headline)
                Text("\(account.loginName) · \(account.personNo)").font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 5) {
                    StatusLabel(text: account.lifecycleStatus, emphasized: account.lifecycleStatus == "ACTIVE")
                    Text(account.roleCodes.map(UiText.label).joined(separator: " / ")).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 4)
            Button { model.loadAccountDetail(id: account.id) } label: {
                Image(systemName: "info.circle").frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("查看 \(account.displayName) 详情")
        }
        .padding(.vertical, 4)
    }
}

private enum BulkAction: String, Identifiable {
    case reset, graduate, delete
    var id: String { rawValue }
    var path: String { self == .reset ? "reset-password" : rawValue }
    var confirmationTitle: String {
        switch self {
        case .reset: "确认重置密码？"
        case .graduate: "确认移入毕业生账户？"
        case .delete: "确认删除账户？"
        }
    }
    var buttonTitle: String {
        switch self {
        case .reset: "重置密码"
        case .graduate: "移入毕业生"
        case .delete: "删除账户"
        }
    }
}

private struct AccountCreateSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var loginName = ""
    @State private var displayName = ""
    @State private var personNo = ""
    @State private var gender = ""
    @State private var classId = ""
    @State private var roleCode = "USER"
    @State private var scopeIds = Set<String>()
    @State private var reason = "创建账户"

    var body: some View {
        NavigationStack {
            Form {
                Section("账户") {
                    TextField("登录账号", text: $loginName).shtongField()
                    TextField("姓名", text: $displayName).shtongField()
                    TextField("学工号", text: $personNo).shtongField()
                    if roleCode == "USER" {
                        Picker("性别（选填）", selection: $gender) {
                            Text("未填写").tag("")
                            Text("男").tag("MALE")
                            Text("女").tag("FEMALE")
                            Text("其他").tag("OTHER")
                        }
                        Picker("所属班级（必选）", selection: $classId) {
                            Text("未选择").tag("")
                            ForEach(model.orgUnits.filter { $0.type == "CLASS" && $0.status == "ACTIVE" }) { unit in
                                Text(organizationName(unit.id, units: model.orgUnits)).tag(unit.id)
                            }
                        }
                    }
                }
                Section("初始身份与范围") {
                    Picker("身份", selection: $roleCode) {
                        ForEach(Contract.initialRoles, id: \.self) { Text(UiText.label($0)).tag($0) }
                    }
                    ScopeChoices(units: model.orgUnits, role: roleCode, selected: $scopeIds)
                        .onChange(of: roleCode) { _, value in
                            scopeIds.removeAll()
                            if value != "USER" { gender = ""; classId = "" }
                        }
                }
                Section("操作依据") { TextField("原因", text: $reason).shtongField() }
                FeedbackBanner(error: model.error, notice: nil)
            }
            .onAppear { model.error = nil; model.loadEditorData() }
            .interactiveDismissDisabled(model.busy)
            .navigationTitle("新增账户")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        model.createAccount(
                            loginName: loginName,
                            displayName: displayName,
                            personNo: personNo,
                            gender: gender,
                            classId: classId,
                            roleCode: roleCode,
                            scopeIds: Array(scopeIds),
                            reason: reason,
                            onSuccess: { dismiss() }
                        )
                    }
                    .disabled(model.busy || loginName.isEmpty || displayName.isEmpty || personNo.isEmpty || reason.count < 2 || (roleCode == "USER" ? classId.isEmpty : scopeIds.isEmpty))
                }
            }
        }
    }
}

private struct AccountDetailSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let detail: AccountDetail

    private var isStudent: Bool { detail.roleCodes.contains("USER") || detail.lifecycleStatus == "GRADUATED" }

    var body: some View {
        NavigationStack {
            List {
                Section("基本信息") {
                    LabeledContent("登录账号", value: detail.loginName)
                    LabeledContent("姓名", value: detail.displayName)
                    LabeledContent("学号/工号", value: detail.personNo)
                    LabeledContent("身份", value: detail.roleCodes.map(UiText.label).joined(separator: "、"))
                    if isStudent {
                        LabeledContent("性别", value: detail.gender.map(UiText.label) ?? "未填写")
                        LabeledContent("民族", value: detail.ethnicity ?? "未填写")
                        LabeledContent("年龄", value: detail.age.map { String($0) } ?? "未填写")
                    }
                }
                if isStudent {
                    Section("学籍信息") {
                        ForEach(Array(detail.organizationPath.enumerated()), id: \.offset) { entry in
                            LabeledContent(UiText.label(entry.element.type), value: entry.element.name)
                        }
                    }
                    Section("联系方式与地址") {
                        LabeledContent("身份证号", value: detail.idCardNumber ?? "尚未由本人完善")
                        LabeledContent("手机号", value: detail.mobileNumber ?? "尚未由本人完善")
                        LabeledContent("现家庭住址", value: detail.currentAddress.map(fullAddress) ?? "尚未由本人完善")
                        LabeledContent("户籍地址", value: detail.householdAddress.map(fullAddress) ?? "尚未由本人完善")
                    }
                }
                Section("账户状态") {
                    LabeledContent("使用状态", value: UiText.label(detail.lifecycleStatus))
                    LabeledContent("安全状态", value: UiText.label(detail.securityStatus))
                    LabeledContent("最近登录", value: detail.lastLoginAt ?? "暂无")
                }
                if detail.lifecycleStatus == "GRADUATED" {
                    Section("毕业信息") {
                        LabeledContent("毕业时间", value: detail.graduatedAt ?? "历史档案")
                        LabeledContent("毕业原因", value: detail.graduatedReason ?? "历史毕业档案")
                    }
                }
                Section("可执行操作") {
                    if detail.lifecycleStatus == "ACTIVE" {
                    NavigationLink {
                        AccountCorrectionView(detail: detail)
                    } label: {
                        Label("更正账户资料", systemImage: "pencil")
                            .frame(minHeight: 44)
                    }
                    }
                    if isStudent && detail.lifecycleStatus == "ACTIVE" {
                        NavigationLink {
                            EnrollmentChangeView(detail: detail)
                        } label: {
                            Label("办理学籍变动", systemImage: "arrow.triangle.branch")
                                .frame(minHeight: 44)
                        }
                    }
                }
            }
            .overlay { if model.busy { BusyOverlay() } }
            .navigationTitle("账户详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

private struct AccountCorrectionView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let detail: AccountDetail
    @State private var loginName: String
    @State private var displayName: String
    @State private var gender: String
    @State private var idCardNumber: String
    @State private var mobileNumber: String
    @State private var currentProvince: String
    @State private var currentCity: String
    @State private var currentDistrict: String
    @State private var currentDetail: String
    @State private var householdProvince: String
    @State private var householdCity: String
    @State private var householdDistrict: String
    @State private var householdDetail: String
    @State private var reason = "更正账户资料"

    init(detail: AccountDetail) {
        self.detail = detail
        _loginName = State(initialValue: detail.loginName)
        _displayName = State(initialValue: detail.displayName)
        _gender = State(initialValue: detail.gender ?? "")
        _idCardNumber = State(initialValue: detail.idCardNumber ?? "")
        _mobileNumber = State(initialValue: detail.mobileNumber ?? "")
        _currentProvince = State(initialValue: detail.currentAddress?.provinceCode ?? "")
        _currentCity = State(initialValue: detail.currentAddress?.cityCode ?? "")
        _currentDistrict = State(initialValue: detail.currentAddress?.districtCode ?? "")
        _currentDetail = State(initialValue: detail.currentAddress?.detail ?? "")
        _householdProvince = State(initialValue: detail.householdAddress?.provinceCode ?? "")
        _householdCity = State(initialValue: detail.householdAddress?.cityCode ?? "")
        _householdDistrict = State(initialValue: detail.householdAddress?.districtCode ?? "")
        _householdDetail = State(initialValue: detail.householdAddress?.detail ?? "")
    }

    var body: some View {
        Form {
            Section {
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            .listRowBackground(Color.clear)
            Section("可更正信息") {
                TextField("登录账号", text: $loginName).shtongField()
                TextField("姓名", text: $displayName).shtongField()
                LabeledContent("学号/工号", value: detail.personNo)
                if detail.roleCodes.contains("USER") {
                    Picker("性别（选填）", selection: $gender) {
                        Text("未填写").tag("")
                        Text("男").tag("MALE")
                        Text("女").tag("FEMALE")
                        Text("其他").tag("OTHER")
                    }
                }
            }
            if detail.roleCodes.contains("USER"), detail.profileCompleted {
                Section("身份证号与手机号") {
                    TextField("身份证号", text: $idCardNumber).textInputAutocapitalization(.characters)
                    TextField("手机号", text: $mobileNumber).keyboardType(.phonePad)
                }
                addressEditor(title: "现家庭住址", kind: "current", cities: model.currentCities, districts: model.currentDistricts, province: $currentProvince, city: $currentCity, district: $currentDistrict, detailText: $currentDetail)
                addressEditor(title: "户籍地址", kind: "household", cities: model.householdCities, districts: model.householdDistricts, province: $householdProvince, city: $householdCity, district: $householdDistrict, detailText: $householdDetail)
            } else if detail.roleCodes.contains("USER") {
                Section { Text("身份证号、手机号和地址尚未由本人完成首次填写，管理员暂不能代填。") }
            }
            Section("更正原因") {
                TextField("请说明更正原因", text: $reason).shtongField()
            }
            Section {
                Button {
                    model.updateAccount(loginName: loginName, displayName: displayName, gender: gender, reason: reason, idCardNumber: idCardNumber, mobileNumber: mobileNumber, currentAddress: ProfileAddressInput(provinceCode: currentProvince, cityCode: currentCity, districtCode: currentDistrict, detail: currentDetail), householdAddress: ProfileAddressInput(provinceCode: householdProvince, cityCode: householdCity, districtCode: householdDistrict, detail: householdDetail)) {
                        dismiss()
                    }
                } label: {
                    Label(model.busy ? "正在保存" : "保存更正", systemImage: "checkmark")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy || loginName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || reason.count < 2 || !sensitiveFieldsAreValid)
            }
        }
        .scrollContentBackground(.hidden)
        .background(ShtongColor.background)
        .navigationTitle("更正账户资料")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if detail.profileCompleted { model.loadAccountAddressOptions(detail) } }
    }

    @ViewBuilder private func addressEditor(title: String, kind: String, cities: [AdministrativeDivision], districts: [AdministrativeDivision], province: Binding<String>, city: Binding<String>, district: Binding<String>, detailText: Binding<String>) -> some View {
        Section(title) {
            Picker("省/自治区/直辖市", selection: Binding(get: { province.wrappedValue }, set: { value in province.wrappedValue = value; city.wrappedValue = ""; district.wrappedValue = ""; model.selectProfileProvince(kind: kind, code: value) })) { Text("请选择").tag(""); ForEach(model.provinces) { Text($0.name).tag($0.code) } }
            Picker("市", selection: Binding(get: { city.wrappedValue }, set: { value in city.wrappedValue = value; district.wrappedValue = ""; model.selectProfileCity(kind: kind, code: value) })) { Text("请选择").tag(""); ForEach(cities) { Text($0.name).tag($0.code) } }
            Picker("区/县", selection: district) { Text("请选择").tag(""); ForEach(districts) { Text($0.name).tag($0.code) } }
            TextField("详细地址", text: detailText, axis: .vertical).lineLimit(2...4)
        }
    }

    private var sensitiveFieldsAreValid: Bool {
        guard detail.profileCompleted else { return true }
        return idCardNumber.range(of: "^[0-9]{17}[0-9Xx]$", options: .regularExpression) != nil && mobileNumber.range(of: "^1[3-9][0-9]{9}$", options: .regularExpression) != nil && [currentProvince,currentCity,currentDistrict,householdProvince,householdCity,householdDistrict].allSatisfy{!$0.isEmpty} && currentDetail.count >= 2 && householdDetail.count >= 2
    }
}

private func fullAddress(_ value: ProfileAddressDetail) -> String { value.provinceName + value.cityName + value.districtName + value.detail }

private struct EnrollmentChangeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let detail: AccountDetail
    @State private var classId: String
    @State private var reason = "学生学籍变动"

    init(detail: AccountDetail) {
        self.detail = detail
        _classId = State(initialValue: detail.classId ?? "")
    }

    var body: some View {
        Form {
            Section {
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            .listRowBackground(Color.clear)
            Section("当前学籍") {
                LabeledContent("学生", value: "\(detail.displayName)（\(detail.personNo)）")
                LabeledContent("当前班级", value: detail.organizationPath.isEmpty ? "未设置" : detail.organizationPath.map(\.name).joined(separator: " / "))
            }
            Section("转入班级") {
                Picker("新班级", selection: $classId) {
                    Text("请选择班级").tag("")
                    ForEach(model.orgUnits.filter { $0.type == "CLASS" && $0.status == "ACTIVE" }) { unit in
                        Text(organizationName(unit.id, units: model.orgUnits)).tag(unit.id)
                    }
                }
            }
            Section("变动原因") {
                TextField("请说明学籍变动原因", text: $reason).shtongField()
            }
            Section {
                Button {
                    model.changeEnrollment(classId: classId, reason: reason) {
                        dismiss()
                    }
                } label: {
                    Label(model.busy ? "正在办理" : "确认学籍变动", systemImage: "arrow.triangle.branch")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy || classId.isEmpty || classId == detail.classId || reason.count < 2)
            }
        }
        .scrollContentBackground(.hidden)
        .background(ShtongColor.background)
        .navigationTitle("学籍变动")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ImportReviewSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let job: ImportJob
    @State private var reason = "确认批量导入账户"

    var body: some View {
        NavigationStack {
            List {
                FeedbackBanner(error: model.error, notice: nil)
                Section("检查结果") {
                    LabeledContent("状态", value: UiText.label(job.state))
                    LabeledContent("账户行数", value: String(job.rowCount))
    
                }
                if !model.importIssues.isEmpty {
                    Section("问题") {
                        ForEach(model.importIssues) { issue in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("第 \(issue.row) 行 · \(UiText.label(issue.field))").font(.headline)
                                Text(UiText.issue(issue.code)).font(.caption).foregroundStyle(ShtongColor.destructive)
                            }
                        }
                    }
                }
                if job.state == "VALID" {
                    Section("确认") { TextField("提交原因", text: $reason).shtongField() }
                }
                if model.importJob?.state == "COMMITTED" {
                    Button("领取初始密码（仅一次）") { model.downloadImportCredentials() }.disabled(model.busy)
                }
            }
            .navigationTitle("名单检查")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { model.clearImport(); dismiss() }.disabled(model.busy)
                }
                if model.importJob?.state == "VALID" {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("提交导入") { model.commitImport(reason: reason) }.disabled(model.busy || reason.count < 2)
                    }
                }
            }
            .interactiveDismissDisabled(model.busy)
        }
    }
}

struct ExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText, .data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

func splitIds(_ value: String) -> [String] {
    value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
}
