import SwiftUI
import UniformTypeIdentifiers

struct AuditView: View {
    @EnvironmentObject private var model: AppModel
    @State private var eventCode = ""
    @State private var actorAccountId = ""
    @State private var subjectAccountId = ""
    @State private var requestId = ""
    @State private var purpose = "安全审计核查"
    @State private var showFilters = true
    @State private var confirmExport = false
    @State private var showExporter = false

    var body: some View {
        VStack(spacing: 0) {
            FeedbackBanner(error: model.error, notice: model.notice).padding([.horizontal, .top], 12)
            DisclosureGroup("查询条件", isExpanded: $showFilters) {
                VStack(spacing: 8) {
                    Picker("操作类型", selection: $eventCode) {
                        Text("全部操作").tag("")
                        ForEach(UiText.events, id: \.self) { Text(UiText.label($0)).tag($0) }
                    }
                    AccountChoice(selected: $actorAccountId, title: "操作人")
                    AccountChoice(selected: $subjectAccountId, title: "涉及人员")

                    TextField("查询或导出用途", text: $purpose).shtongField()
                    HStack {
                        Button { search() } label: { Label("查询", systemImage: "magnifyingglass") }
                            .buttonStyle(.borderedProminent)
                        Spacer()
                        Button { confirmExport = true } label: { Label("申请导出", systemImage: "square.and.arrow.up") }
                            .buttonStyle(.bordered)
                    }
                    if model.pendingAuditExportJobId != nil {
                        Button { model.refreshAuditExport() } label: { Label("刷新并领取操作记录导出", systemImage: "arrow.clockwise") }
                            .buttonStyle(.bordered)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    if model.exportData != nil { Button("保存待下载文件") { showExporter = true } }
                    .controlSize(.large)
                }
                .padding(.top, 8)
            }
            .padding(12)

            List(model.audits) { event in
                Button { model.loadAuditDetail(id: event.eventId) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(UiText.label(event.eventCode)).font(.headline).foregroundStyle(.primary)
                            Spacer()
                            StatusLabel(text: event.outcome, emphasized: event.outcome == "SUCCESS")
                        }
                        Text(event.occurredAt).font(.caption).foregroundStyle(.secondary)
                        HStack {
                            Text(UiText.label(event.authorizationDecision))
                            Spacer()
                            Text("涉及 \(event.subjectCount ?? 0) 人")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    }
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                .accessibilityHint("打开操作详情")
            }
            .listStyle(.plain)
            .overlay {
                if model.audits.isEmpty && !model.busy {
                    ContentUnavailableView("没有操作记录", systemImage: "doc.text.magnifyingglass", description: Text("调整查询条件后重试。"))
                }
            }
        }
        .background(ShtongColor.background)
        .onAppear { model.loadEditorData() }
        .sheet(item: $model.auditDetail) { AuditDetailSheet(detail: $0) }
        .fileExporter(
            isPresented: $showExporter,
            document: ExportDocument(data: model.exportData ?? Data()),
            contentType: .commaSeparatedText,
            defaultFilename: model.exportFileName
        ) { result in
            if case .success = result { model.clearExport() }
            else if case .failure(let error) = result { model.error = "文件未保存，请重试：" + error.localizedDescription }
        }
        .onChange(of: model.exportData) { _, value in if value != nil { showExporter = true } }
        .confirmationDialog("申请导出操作记录？", isPresented: $confirmExport, titleVisibility: .visible) {
            Button("提交双人复核") {
                model.requestAuditExport(reason: purpose, eventCode: eventCode, actorAccountId: actorAccountId, subjectAccountId: subjectAccountId)
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("导出最近 31 天且符合当前条件的记录。用途：\(purpose)")
        }
    }

    private func search() {
        model.loadAudit(eventCode: eventCode, actorAccountId: actorAccountId, subjectAccountId: subjectAccountId, requestId: requestId)
    }
}

private struct AuditDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    let detail: AuditEventDetail

    var body: some View {
        NavigationStack {
            List {
                Section("事件") {
                    LabeledContent("操作", value: UiText.label(detail.eventCode))
                    LabeledContent("发生时间", value: detail.occurredAt)
                    LabeledContent("结果", value: UiText.label(detail.outcome))
                    LabeledContent("权限校验", value: UiText.label(detail.authorizationDecision))
                    LabeledContent("工作区", value: UiText.label(detail.workspace ?? "未选择"))
                    LabeledContent("渠道", value: UiText.label(detail.channel ?? "未记录"))
                    LabeledContent("涉及人数", value: String(detail.subjectCount ?? 0))
                }
                Section {
                    DisclosureGroup("技术排障明细") {
                        LabeledContent("原因", value: UiText.label(detail.reasonCode ?? "未记录"))
                        LabeledContent("操作人编号", value: detail.actorAccountId ?? "系统或未登录人员")
                        LabeledContent("请求编号", value: detail.requestId)
                        ForEach(detail.subjects ?? [], id: \.self) { LabeledContent("涉及人员编号", value: $0) }
                        JSONSection(title: "对象", values: detail.resources ?? [])
                        JSONSection(title: "内容变化", values: detail.changes ?? [])
                        JSONSection(title: "权限校验依据", values: detail.authorizationEvidence ?? [])
                    }
                }
            }
            .navigationTitle("操作详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

private struct JSONSection: View {
    let title: String
    let values: [JSONObject]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            if values.isEmpty { Text("无").foregroundStyle(.secondary) }
            ForEach(Array(values.enumerated()), id: \.offset) { _, object in
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(object.keys.sorted(), id: \.self) { key in
                        LabeledContent(UiText.label(key), value: render(object[key]))
                    }
                }
            }
        }
    }

    private func render(_ value: JSONValue?) -> String {
        switch value {
        case .string(let value): UiText.label(value)
        case .number(let value): String(value)
        case .bool(let value): value ? "是" : "否"
        case .array(let value): "[\(value.count) 项]"
        case .object(let value): "{\(value.count) 项}"
        case .null, .none: "无"
        }
    }
}
