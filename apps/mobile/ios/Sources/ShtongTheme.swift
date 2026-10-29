import SwiftUI
import UIKit

enum ShtongColor {
    static let primary = dynamic(light: (15,118,110), dark: (94,234,212))
    static let secondary = dynamic(light: (51,65,85), dark: (203,213,225))
    static let accent = dynamic(light: (180,83,9), dark: (251,191,36))
    static let background = dynamic(light: (246,248,247), dark: (16,24,23))
    static let foreground = dynamic(light: (23,32,31), dark: (230,239,237))
    static let muted = dynamic(light: (231,236,234), dark: (38,57,54))
    static let mutedForeground = dynamic(light: (82,96,93), dark: (169,187,183))
    static let border = dynamic(light: (203,213,209), dark: (70,98,94))
    static let destructive = dynamic(light: (185,28,28), dark: (248,113,113))

    private static func dynamic(light:(CGFloat,CGFloat,CGFloat),dark:(CGFloat,CGFloat,CGFloat))->Color {
        Color(uiColor:UIColor{traits in let value=traits.userInterfaceStyle == .dark ? dark : light;return UIColor(red:value.0/255,green:value.1/255,blue:value.2/255,alpha:1)})
    }
}

struct FeedbackBanner: View {
    let error: String?
    let notice: String?

    var body: some View {
        if let error {
            Label(error, systemImage: "exclamationmark.triangle.fill")
                .font(.callout.weight(.medium))
                .foregroundStyle(ShtongColor.destructive)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(ShtongColor.destructive.opacity(0.09))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .accessibilityLabel("错误：\(error)")
        } else if let notice {
            Label(notice, systemImage: "checkmark.circle.fill")
                .font(.callout.weight(.medium))
                .foregroundStyle(ShtongColor.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(ShtongColor.primary.opacity(0.09))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .accessibilityLabel("提示：\(notice)")
        }
    }
}

struct StatusLabel: View {
    let text: String
    var emphasized = false

    var body: some View {
        Text(UiText.label(text))
            .font(.caption.weight(.semibold))
            .foregroundStyle(emphasized ? ShtongColor.primary : ShtongColor.mutedForeground)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background((emphasized ? ShtongColor.primary : ShtongColor.mutedForeground).opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

struct BusyOverlay: View {
    var body: some View {
        ZStack {
            Color.black.opacity(0.08).ignoresSafeArea()
            ProgressView("正在处理")
                .padding(20)
                .background(.regularMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .accessibilityAddTraits(.isModal)
    }
}

extension View {
    func shtongField() -> some View {
        textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
    }
}

func organizationName(_ id: String?, units: [OrgUnit]) -> String {
    guard let id, !id.isEmpty else { return "未设置" }
    var names: [String] = [], seen = Set<String>()
    var current = units.first { $0.id == id }
    while let unit = current, seen.insert(unit.id).inserted {
        names.insert(unit.name, at: 0)
        current = units.first { $0.id == unit.parentId }
    }
    return names.isEmpty ? "组织已不可用" : names.joined(separator: " / ")
}

struct AccountChoice: View {
    @EnvironmentObject private var model: AppModel
    @Binding var selected: String
    var title = "选择账户"
    @State private var query = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("姓名、登录账号或学工号", text: $query).shtongField().accessibilityLabel("查找账户")
            Button("查找") { model.searchAccountOptions(query: query) }.disabled(model.busy).frame(minHeight: 44)
            Picker(title, selection: $selected) {
                Text("未选择").tag("")
                ForEach(model.accountOptions) { account in
                    Text("\(account.displayName) · \(account.loginName) · \(account.personNo)").tag(account.id)
                }
            }
            .pickerStyle(.menu)
            .frame(minHeight: 44)
            .disabled(model.busy)
            if model.accountOptionsNext != nil {
                Button("更多账户") { model.searchAccountOptions(query: query, more: true) }.disabled(model.busy).frame(minHeight: 44)
            }
        }
    }
}

struct ScopeChoices: View {
    let units: [OrgUnit]
    let role: String
    @Binding var selected: Set<String>
    @State private var query = ""
    private var options: [OrgUnit] {
        units.filter { unit in
            unit.status == "ACTIVE" && (role == "TEACHER_ADMIN" ? unit.type == "GRADE" : ["COLLEGE", "GRADE", "CLASS"].contains(unit.type)) && (query.isEmpty || organizationName(unit.id, units: units).contains(query))
        }
    }
    var body: some View {
        if role == "TEACHER_ADMIN" || role == "STUDENT_ADMIN" || role == "MEDICAL_INSURANCE_ADMIN" {
            VStack(alignment: .leading, spacing: 8) {
                Text("管理范围（已选 \(selected.count) 项）").font(.headline)
                TextField("查找管理范围", text: $query).shtongField()
                if options.isEmpty { Text("没有可选范围").foregroundStyle(.secondary) }
                ForEach(options) { unit in
                    Toggle(organizationName(unit.id, units: units), isOn: Binding(get: { selected.contains(unit.id) }, set: { value in
                        if value { selected.insert(unit.id) } else { selected.remove(unit.id) }
                    })).frame(minHeight: 44)
                }
            }
        }
    }
}
