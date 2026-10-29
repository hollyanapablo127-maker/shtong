import SwiftUI
import UIKit

private struct SensitiveCopyRequest: Identifiable {
    let id: String
    let label: String
}

struct ProfileView: View {
    @EnvironmentObject private var model: AppModel
    @State private var confirmLogout = false
    @State private var sensitiveRequest: SensitiveCopyRequest?
    @State private var verificationPassword = ""

    private var isStudent: Bool { model.profile?.roleCodes.contains("USER") == true }

    var body: some View {
        Form {
            Section {
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            .listRowBackground(Color.clear)
            Section("基本信息") {
                copyRow("姓名", model.profile?.displayName ?? "-")
                copyRow("学号/工号", model.profile?.personNo ?? "-")
                copyRow("身份", model.profile?.roleCodes.map(UiText.label).joined(separator: "、") ?? "-")
                if isStudent {
                    copyRow("性别", model.profile?.gender.map(UiText.label) ?? "未填写")
                    copyRow("民族", model.profile?.ethnicity ?? "未填写")
                    LabeledContent("年龄", value: model.profile?.age.map(String.init) ?? "-")
                }
            }

            if isStudent {
                Section("学籍信息") {
                    ForEach(Array((model.profile?.organizationPath ?? []).enumerated()), id: \.offset) { entry in
                        copyRow(UiText.label(entry.element.type), entry.element.name)
                    }
                    copyRow("对应辅导员", counselorText)
                }
                Section("联系方式与地址") {
                    sensitiveRow("身份证号", model.profile?.idCardNumberMasked ?? "-", field: "ID_CARD_NUMBER")
                    sensitiveRow("手机号", model.profile?.mobileNumberMasked ?? "-", field: "MOBILE_NUMBER")
                    sensitiveRow("现家庭住址", model.profile?.currentAddressMasked ?? "-", field: "CURRENT_ADDRESS")
                    sensitiveRow("户籍地址", model.profile?.householdAddressMasked ?? "-", field: "HOUSEHOLD_ADDRESS")
                }
            }

            if let scopes = model.profile?.managedScopes, !scopes.isEmpty {
                Section("管辖范围") {
                    ForEach(Array(scopes.enumerated()), id: \.offset) { entry in
                        copyRow("\(UiText.label(entry.element.roleCode))管辖范围", entry.element.scopes.map(\.path).joined(separator: "、").nilIfEmpty ?? "-")
                    }
                }
            }

            Section {
                NavigationLink {
                    ProfilePasswordView()
                } label: {
                    Label("修改密码", systemImage: "key")
                        .frame(minHeight: 44)
                }
            }
            Section {
                Button(role: .destructive) { confirmLogout = true } label: {
                    Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(ShtongColor.background)
        .onAppear {
            if model.profile == nil { model.loadProfile() }
        }
        .confirmationDialog("退出生化通？", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("退出登录", role: .destructive, action: model.logout)
            Button("取消", role: .cancel) {}
        } message: {
            Text("本机凭据和受保护导航状态将被清除。")
        }
        .sheet(item: $sensitiveRequest) { request in
            NavigationStack {
                Form {
                    Section {
                        Text("为保护个人信息，请输入当前登录密码。验证成功后内容会直接复制，不会在页面显示。")
                            .foregroundStyle(.secondary)
                        SecureField("当前密码", text: $verificationPassword).textContentType(.password)
                        FeedbackBanner(error: model.error, notice: nil)
                    }
                    Section {
                        Button {
                            model.revealSensitiveProfileField(request.id, password: verificationPassword) { value in
                                UIPasteboard.general.string = value
                                sensitiveRequest = nil
                                verificationPassword = ""
                            }
                        } label: {
                            Label(model.busy ? "正在验证" : "验证并复制", systemImage: "doc.on.doc")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.busy || verificationPassword.isEmpty)
                    }
                }
                .navigationTitle("复制\(request.label)")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { sensitiveRequest = nil; verificationPassword = "" } } }
            }
            .presentationDetents([.medium])
        }
    }

    private var counselorText: String {
        let values = model.profile?.counselors ?? []
        return values.isEmpty ? "暂未匹配" : values.map { "\($0.displayName)（\($0.personNo)）" }.joined(separator: "、")
    }

    @ViewBuilder private func copyRow(_ label: String, _ value: String) -> some View {
        Button { copy(label, value) } label: {
            LabeledContent(label) { HStack { Text(value).multilineTextAlignment(.trailing); Image(systemName: "doc.on.doc") } }
        }.buttonStyle(.plain)
    }

    @ViewBuilder private func sensitiveRow(_ label: String, _ value: String, field: String) -> some View {
        Button { model.error = nil; sensitiveRequest = SensitiveCopyRequest(id: field, label: label) } label: {
            LabeledContent(label) { HStack { Text(value).multilineTextAlignment(.trailing); Image(systemName: "lock.shield") } }
        }.buttonStyle(.plain)
    }

    private func copy(_ label: String, _ value: String) {
        guard value != "-", !value.isEmpty else { return }
        UIPasteboard.general.string = value
        model.notice = "\(label)已复制"
    }
}

private struct ProfilePasswordView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmation = ""

    var body: some View {
        Form {
            Section {
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            .listRowBackground(Color.clear)
            Section("修改密码") {
                SecureField("当前密码", text: $currentPassword).shtongField().textContentType(.password)
                SecureField("新密码（至少 8 位，含大小写和数字）", text: $newPassword).shtongField().textContentType(.newPassword)
                SecureField("确认新密码", text: $confirmation).shtongField().textContentType(.newPassword)
            }
            Section {
                Button {
                    model.changeProfilePassword(current: currentPassword, new: newPassword, confirmation: confirmation) {
                        dismiss()
                    }
                } label: {
                    Label(model.busy ? "正在修改" : "确认修改", systemImage: "key")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy || currentPassword.isEmpty || !newPassword.passwordPolicyValid || confirmation.isEmpty)
            }
        }
        .scrollContentBackground(.hidden)
        .background(ShtongColor.background)
        .navigationTitle("修改密码")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.error = nil; model.notice = nil }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
    var passwordPolicyValid: Bool {
        count >= 8 && range(of: "[A-Z]", options: .regularExpression) != nil && range(of: "[a-z]", options: .regularExpression) != nil && range(of: "[0-9]", options: .regularExpression) != nil
    }
}
