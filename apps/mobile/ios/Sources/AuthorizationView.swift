import SwiftUI

struct AuthorizationView: View {
    @EnvironmentObject private var model: AppModel
    @State private var mode = Mode.assignments
    @State private var accountId = ""
    @State private var roleCode = "TEACHER_ADMIN"
    @State private var scopeIds = Set<String>()
    @State private var reason = "调整账户管辖范围"
    @State private var revokeTarget: RoleAssignment?
    @State private var pendingControl: ControlRequest?
    @State private var approveControl = false

    private enum Mode: String, CaseIterable, Identifiable {
        case assignments = "身份与范围"
        case controls = "安全复核"
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("授权视图", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(12)
            .onChange(of: mode) { _, value in if value == .controls { model.loadControls() } }
            FeedbackBanner(error: model.error, notice: model.notice).padding(.horizontal, 12)
            if mode == .assignments { assignmentContent } else { controlsContent }
        }
        .background(ShtongColor.background)
        .onAppear { model.loadEditorData() }
        .confirmationDialog(approveControl ? "同意并执行申请？" : "拒绝申请？", isPresented: Binding(get: { pendingControl != nil }, set: { if !$0 { pendingControl = nil } }), titleVisibility: .visible) {
            Button(approveControl ? "同意并执行" : "拒绝申请", role: approveControl ? nil : .destructive) {
                if let pendingControl { model.decideControl(pendingControl, approve: approveControl, reason: reason) }
                pendingControl = nil
            }
            Button("取消", role: .cancel) { pendingControl = nil }
        } message: { Text(pendingControl.map { UiText.label($0.commandType) + "：" + $0.reason } ?? "") }
        .confirmationDialog("取消身份授权？", isPresented: Binding(get: { revokeTarget != nil }, set: { if !$0 { revokeTarget = nil } }), titleVisibility: .visible) {
            if let revokeTarget {
                Button("取消 \(UiText.label(revokeTarget.roleCode))", role: .destructive) {
                    model.revokeAssignment(id: revokeTarget.id, reason: reason, accountId: accountId)
                    self.revokeTarget = nil
                }
            }
            Button("取消", role: .cancel) { revokeTarget = nil }
        } message: {
            Text("此账户现有登录将失效，需要重新登录。原因：\(reason)")
        }
    }

    private var assignmentContent: some View {
        List {
            Section("选择账户") {
                AccountChoice(selected: $accountId)
                    .onChange(of: accountId) { _, id in model.assignments = []; if !id.isEmpty { model.loadAssignments(accountId: id) } }
                HStack {
                    Button { model.loadAssignments(accountId: accountId) } label: { Label("刷新身份", systemImage: "arrow.clockwise") }
                    Spacer()
                    Button { model.addAssignment(accountId: accountId, roleCode: roleCode, scopeIds: Array(scopeIds), reason: reason) } label: {
                        Label("添加身份", systemImage: "checkmark.shield")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.busy || accountId.isEmpty || reason.count < 2 || (["TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN"].contains(roleCode) && scopeIds.isEmpty) || model.assignments.contains { $0.roleCode == roleCode && $0.status == "ACTIVE" })
                }
            }
            Section("新增身份") {
                Picker("身份", selection: $roleCode) {
                    ForEach(Contract.roles, id: \.self) { Text(UiText.label($0)).tag($0) }
                }
                ScopeChoices(units: model.orgUnits, role: roleCode, selected: $scopeIds)
                    .onChange(of: roleCode) { _, _ in scopeIds.removeAll() }
                TextField("原因", text: $reason).shtongField()
            }
            Section("现有身份") {
                if model.assignments.isEmpty {
                    Text("暂无已读取的授权").foregroundStyle(.secondary)
                }
                ForEach(model.assignments) { item in
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 5) {
                            HStack { Text(UiText.label(item.roleCode)).font(.headline); StatusLabel(text: item.status, emphasized: item.status == "ACTIVE") }
                            Text(item.scopeCeilings.map { organizationName($0.id, units: model.orgUnits) }.joined(separator: "；").isEmpty ? "本人班级或系统账户管理" : item.scopeCeilings.map { organizationName($0.id, units: model.orgUnits) }.joined(separator: "；"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text("生效：\(item.validFrom)").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(role: .destructive) { revokeTarget = item } label: { Image(systemName: "shield.slash") }
                            .frame(width: 44, height: 44)
                            .accessibilityLabel("取消 \(UiText.label(item.roleCode))")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var controlsContent: some View {
        List {
            Section("复核原因") { TextField("决定原因", text: $reason).shtongField() }
            Section("待复核请求") {
                if model.controls.isEmpty { Text("当前没有复核请求").foregroundStyle(.secondary) }
                ForEach(model.controls) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(UiText.label(item.commandType)).font(.headline); Spacer(); StatusLabel(text: item.state, emphasized: item.state == "PENDING") }
                        Text(item.reason).font(.callout).foregroundStyle(.secondary)
                        HStack {
                            Button("拒绝", role: .destructive) { pendingControl = item; approveControl = false }
                            Spacer()
                            Button("同意") { pendingControl = item; approveControl = true }
                                .buttonStyle(.borderedProminent)
                        }
                        .disabled(model.busy || item.state != "PENDING" || reason.count < 2)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .listStyle(.insetGrouped)
        .refreshable { model.loadControls() }
    }
}
