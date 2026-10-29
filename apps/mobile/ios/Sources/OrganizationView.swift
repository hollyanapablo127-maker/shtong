import SwiftUI

struct OrganizationView: View {
    @EnvironmentObject private var model: AppModel
    @State private var type = "SCHOOL"
    @State private var parentId = ""
    @State private var code = ""
    @State private var name = ""
    @State private var reason = "维护组织基础数据"
    @State private var editing: OrgUnit?
    @State private var deleteTarget: OrgUnit?

    var body: some View {
        List {
            Section {
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            .listRowBackground(Color.clear)
            Section("新增学校组织") {
                Picker("类型", selection: $type) {
                    ForEach(["SCHOOL", "COLLEGE", "GRADE", "CLASS"], id: \.self) { Text(UiText.label($0)).tag($0) }
                }
                if type != "SCHOOL" {
                    Picker("所属上一级", selection: $parentId) {
                        Text("请选择").tag("")
                        ForEach(model.orgUnits.filter { $0.status == "ACTIVE" && $0.type == ["COLLEGE": "SCHOOL", "GRADE": "COLLEGE", "CLASS": "GRADE"][type] }) { unit in
                            Text(organizationName(unit.id, units: model.orgUnits)).tag(unit.id)
                        }
                    }
                }
                TextField("组织编号（留空自动生成）", text: $code).shtongField()
                TextField("名称", text: $name).shtongField()
                TextField("原因", text: $reason).shtongField()
                Button {
                    model.createOrganization(type: type, parentId: parentId, code: code, name: name, reason: reason) { code = ""; name = "" }
                } label: {
                    Label("创建学校组织", systemImage: "plus")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy || name.isEmpty || reason.count < 2 || (type != "SCHOOL" && parentId.isEmpty))
            }
            Section("学校组织") {
                if model.orgUnits.isEmpty { Text("暂无学校组织").foregroundStyle(.secondary) }
                ForEach(model.orgUnits) { unit in
                    HStack(spacing: 10) {
                        Image(systemName: "point.3.connected.trianglepath.dotted").foregroundStyle(ShtongColor.primary)
                        VStack(alignment: .leading, spacing: 4) {
                            HStack { Text(organizationName(unit.id, units: model.orgUnits)).font(.headline); StatusLabel(text: unit.status, emphasized: unit.status == "ACTIVE") }
                            Text("\(UiText.label(unit.type)) · \(unit.code)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { editing = unit } label: { Image(systemName: "pencil") }.frame(width: 44, height: 44).accessibilityLabel("编辑 \(unit.name)")
                        Button(role: .destructive) { deleteTarget = unit } label: { Image(systemName: "trash") }
                            .frame(width: 44, height: 44)
                            .accessibilityLabel("删除 \(unit.name)")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .background(ShtongColor.background)
        .refreshable { model.loadOrganizations() }
        .onChange(of: type) { _, _ in parentId = "" }
        .sheet(item: $editing) { unit in OrganizationEditSheet(unit: unit, reason: reason) }
        .confirmationDialog("删除学校组织？", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
            if let deleteTarget {
                Button("删除 \(deleteTarget.name)", role: .destructive) {
                    model.deleteOrganization(deleteTarget, reason: reason)
                    self.deleteTarget = nil
                }
            }
            Button("取消", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("有下级组织、在用账户或权限时不能删除。删除后可重新使用原名称和编号创建。")
        }
    }
}

private struct OrganizationEditSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let unit: OrgUnit
    let reason: String
    @State private var name: String

    init(unit: OrgUnit, reason: String) {
        self.unit = unit
        self.reason = reason
        _name = State(initialValue: unit.name)
    }

    var body: some View {
        NavigationStack {
            Form {
                LabeledContent("类型", value: UiText.label(unit.type))
                LabeledContent("组织编号", value: unit.code)
                TextField("名称", text: $name).shtongField()
                FeedbackBanner(error: model.error, notice: nil)
            }
            .interactiveDismissDisabled(model.busy)
            .navigationTitle("修改名称")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { model.updateOrganization(unit, name: name, reason: reason) { dismiss() } }.disabled(model.busy || name.isEmpty)
                }
            }
        }
    }
}
