import SwiftUI

let profileDefaultEthnicity = ""

struct LoginView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loginName = ""
    @State private var password = ""
    @FocusState private var focus: Field?
    private enum Field { case login, password }

    var body: some View {
        AuthFrame(title: "生化通", subtitle: "账户、权限与审计工作区") {
            TextField("登录账号", text: $loginName)
                .shtongField()
                .textContentType(.username)
                .focused($focus, equals: .login)
                .submitLabel(.next)
                .onSubmit { focus = .password }
            SecureField("密码", text: $password)
                .shtongField()
                .textContentType(.password)
                .focused($focus, equals: .password)
                .submitLabel(.go)
                .onSubmit(submit)
            FeedbackBanner(error: model.error, notice: nil)
            Button(action: submit) {
                Label("登录", systemImage: "arrow.right.circle.fill")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.busy)
        }
        .onAppear { focus = .login }
    }

    private func submit() { model.login(loginName: loginName, password: password) }
}

struct PasswordChangeView: View {
    @EnvironmentObject private var model: AppModel
    let forced: Bool
    @State private var current = ""
    @State private var newPassword = ""
    @State private var confirmation = ""

    var body: some View {
        AuthFrame(
            title: forced ? "设置正式密码" : "修改密码",
            subtitle: forced ? "首次登录或密码重置后必须先完成改密" : "更新后其他旧会话将立即失效"
        ) {
            SecureField(forced ? "临时密码" : "当前密码", text: $current).shtongField().textContentType(.password)
            SecureField("新密码（至少 8 位，含大小写和数字）", text: $newPassword).shtongField().textContentType(.newPassword)
            SecureField("确认新密码", text: $confirmation).shtongField().textContentType(.newPassword)
            FeedbackBanner(error: model.error, notice: model.notice)
            Button {
                model.changePassword(current: current, new: newPassword, confirmation: confirmation)
            } label: {
                Label("更新密码", systemImage: "key.fill")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            if forced {
                Button("退出", role: .cancel, action: model.logout)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
        }
    }
}

struct ProfileCompletionView: View {
    @EnvironmentObject private var model: AppModel
    @State private var idCardNumber = ""
    @State private var mobileNumber = ""
    @State private var ethnicity = profileDefaultEthnicity
    @State private var currentProvince = ""
    @State private var currentCity = ""
    @State private var currentDistrict = ""
    @State private var currentDetail = ""
    @State private var householdProvince = ""
    @State private var householdCity = ""
    @State private var householdDistrict = ""
    @State private var householdDetail = ""

    var body: some View {
        Form {
            Section {
                Text("首次设置正式密码后需完成以下资料，保存后才能进入工作台。")
                    .foregroundStyle(.secondary)
                FeedbackBanner(error: model.error, notice: model.notice)
            }
            Section("只读账户资料") {
                LabeledContent("姓名", value: model.profile?.displayName ?? "-")
                LabeledContent("学号", value: model.profile?.personNo ?? "-")
                ForEach(Array((model.profile?.organizationPath ?? []).enumerated()), id: \.offset) { entry in
                    LabeledContent(UiText.label(entry.element.type), value: entry.element.name)
                }
            }
            Section("身份与联系方式") {
                TextField("身份证号（18位）", text: $idCardNumber).textInputAutocapitalization(.characters).textContentType(.none)
                SearchablePicker(title: "民族", selection: $ethnicity, options: ethnicityOptions)
                LabeledContent("年龄（自动计算）", value: derivedAge.map(String.init) ?? "输入身份证号后自动计算")
                TextField("手机号", text: $mobileNumber).keyboardType(.phonePad).textContentType(.telephoneNumber)
            }
            addressSection(title: "现家庭住址", kind: "current", provinces: model.provinces, cities: model.currentCities, districts: model.currentDistricts, province: $currentProvince, city: $currentCity, district: $currentDistrict, detail: $currentDetail)
            addressSection(title: "户籍地址", footer: "请按户口簿或身份证登记地址填写。", kind: "household", provinces: model.provinces, cities: model.householdCities, districts: model.householdDistricts, province: $householdProvince, city: $householdCity, district: $householdDistrict, detail: $householdDetail)
            Section {
                Button {
                    model.completeProfile(
                        idCardNumber: idCardNumber.trimmingCharacters(in: .whitespacesAndNewlines),
                        mobileNumber: mobileNumber.trimmingCharacters(in: .whitespacesAndNewlines),
                        ethnicity: ethnicity,
                        currentAddress: ProfileAddressInput(provinceCode: currentProvince, cityCode: currentCity, districtCode: currentDistrict, detail: currentDetail.trimmingCharacters(in: .whitespacesAndNewlines)),
                        householdAddress: ProfileAddressInput(provinceCode: householdProvince, cityCode: householdCity, districtCode: householdDistrict, detail: householdDetail.trimmingCharacters(in: .whitespacesAndNewlines))
                    )
                } label: {
                    Label(model.busy ? "正在保存" : "保存并进入工作台", systemImage: "checkmark.shield")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy || !formIsValid)
                Button("退出登录", role: .cancel, action: model.logout)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
        }
        .scrollContentBackground(.hidden)
        .background(ShtongColor.background)
        .navigationTitle("完善个人信息")
        .navigationBarBackButtonHidden(true)
        .onAppear(perform: model.loadProfileCompletionOptions)
    }

    @ViewBuilder private func addressSection(title: String, footer: String? = nil, kind: String, provinces: [AdministrativeDivision], cities: [AdministrativeDivision], districts: [AdministrativeDivision], province: Binding<String>, city: Binding<String>, district: Binding<String>, detail: Binding<String>) -> some View {
        Section {
            SearchablePicker(title: "省/自治区/直辖市", selection: province, options: provinces.map { ($0.code, $0.name) })
                .onChange(of: province.wrappedValue) { _, value in city.wrappedValue = ""; district.wrappedValue = ""; model.selectProfileProvince(kind: kind, code: value) }
            SearchablePicker(title: "市", selection: city, options: cities.map { ($0.code, $0.name) }).disabled(province.wrappedValue.isEmpty)
                .onChange(of: city.wrappedValue) { _, value in district.wrappedValue = ""; model.selectProfileCity(kind: kind, code: value) }
            SearchablePicker(title: "区/县", selection: district, options: districts.map { ($0.code, $0.name) }).disabled(city.wrappedValue.isEmpty)
            TextField("街道、门牌号、小区、楼栋和房间号", text: detail, axis: .vertical).lineLimit(2...4)
        } header: { Text(title) } footer: { if let footer { Text(footer) } }
    }

    private var formIsValid: Bool {
        !ethnicity.isEmpty && validIdCard && mobileNumber.range(of: "^1[3-9][0-9]{9}$", options: .regularExpression) != nil &&
        [currentProvince, currentCity, currentDistrict, householdProvince, householdCity, householdDistrict].allSatisfy { !$0.isEmpty } &&
        currentDetail.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 && householdDetail.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2
    }

    private var validIdCard: Bool {
        let value = Array(idCardNumber.trimmingCharacters(in: .whitespacesAndNewlines).uppercased())
        guard value.count == 18, value.prefix(17).allSatisfy({ $0.isNumber }), value[17].isNumber || value[17] == "X" else { return false }
        let weights = [7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2], checks = Array("10X98765432")
        let sum = zip(value.prefix(17), weights).reduce(0) { $0 + (Int(String($1.0)) ?? 0) * $1.1 }
        return checks[sum % 11] == value[17]
    }

    private var derivedAge: Int? {
        let value = idCardNumber.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard value.count == 18, let year = Int(value.dropFirst(6).prefix(4)), let month = Int(value.dropFirst(10).prefix(2)), let day = Int(value.dropFirst(12).prefix(2)), let birth = Calendar.current.date(from: DateComponents(year: year, month: month, day: day)) else { return nil }
        return Calendar.current.dateComponents([.year], from: birth, to: Date()).year
    }
}

private let ethnicityOptions: [(String, String)] = ["汉族","蒙古族","回族","藏族","维吾尔族","苗族","彝族","壮族","布依族","朝鲜族","满族","侗族","瑶族","白族","土家族","哈尼族","哈萨克族","傣族","黎族","傈僳族","佤族","畲族","高山族","拉祜族","水族","东乡族","纳西族","景颇族","柯尔克孜族","土族","达斡尔族","仫佬族","羌族","布朗族","撒拉族","毛难族","仡佬族","锡伯族","阿昌族","普米族","塔吉克族","怒族","乌孜别克族","俄罗斯族","鄂温克族","崩龙族","保安族","裕固族","京族","塔塔尔族","独龙族","鄂伦春族","赫哲族","门巴族","珞巴族","基诺族"].map { ($0, $0) }

private struct SearchablePicker: View {
    let title: String
    @Binding var selection: String
    let options: [(String, String)]
    @State private var presented = false
    @State private var query = ""
    private var selectedName: String { options.first(where: { $0.0 == selection })?.1 ?? "请选择" }
    var body: some View {
        Button { query = ""; presented = true } label: { LabeledContent(title, value: selectedName) }.sheet(isPresented: $presented) {
            NavigationStack { List(options.filter { query.isEmpty || $0.1.localizedCaseInsensitiveContains(query) }, id: \.0) { item in Button(item.1) { selection = item.0; presented = false } } .searchable(text: $query, prompt: "搜索\(title)").navigationTitle(title).toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { presented = false } } } }
        }
    }
}

private struct AuthFrame<Content: View>: View {
    let title: String
    let subtitle: String
    let content: Content

    init(title: String, subtitle: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("生")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(ShtongColor.primary)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .accessibilityHidden(true)
                Text(title).font(.largeTitle.weight(.bold)).foregroundStyle(ShtongColor.foreground)
                Text(subtitle).font(.body).foregroundStyle(ShtongColor.mutedForeground)
                content
            }
            .frame(maxWidth: 440, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(ShtongColor.background.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
    }
}
