import SwiftUI
import WebKit

struct WorkspaceView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var confirmLogout = false

    private var tabSelection: Binding<AppRoute> {
        Binding(get: { [.home, .profile].contains(model.route) ? model.route : .home }, set: { model.navigate(to: $0) })
    }

    private var topLevelRoutes: [AppRoute] { [.home, .profile] }

    var body: some View {
        Group {
            if model.route == .medicalInsurance {
                MedicalInsuranceView()
            } else if horizontalSizeClass == .regular {
                NavigationSplitView {
                    List(topLevelRoutes) { route in
                        Button { model.navigate(to: route) } label: {
                            Label(route.title, systemImage: route.symbol)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(model.route == route ? ShtongColor.primary : ShtongColor.foreground)
                        .listRowBackground(model.route == route ? ShtongColor.primary.opacity(0.1) : Color.clear)
                    }
                    .navigationTitle("生化通")
                    .safeAreaInset(edge: .bottom) {
                        Button(role: .destructive) { confirmLogout = true } label: {
                            Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                        .padding(.horizontal)
                    }
                } detail: {
                    NavigationStack { WorkspaceContent(route: model.route, confirmLogout: $confirmLogout) }
                }
            } else {
                TabView(selection: tabSelection) {
                    ForEach(topLevelRoutes) { route in
                        NavigationStack { WorkspaceContent(route: route, confirmLogout: $confirmLogout) }
                            .tabItem { Label(route.title, systemImage: route.symbol) }
                            .tag(route)
                    }
                }
            }
        }
        .confirmationDialog("退出生化通？", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("退出登录", role: .destructive, action: model.logout)
            Button("取消", role: .cancel) {}
        } message: {
            Text("本机凭据和受保护导航状态将被清除。")
        }
    }
}

private struct WorkspaceContent: View {
    @EnvironmentObject private var model: AppModel
    let route: AppRoute
    @Binding var confirmLogout: Bool

    var body: some View {
        let displayedRoute = route == .home && ![.home, .profile].contains(model.route) ? model.route : route
        Group {
            switch displayedRoute {
            case .home: ModuleGridView()
            case .accounts: AccountsView().id("accounts")
            case .graduates: AccountsView(graduates: true).id("graduates")
            case .authorization: AuthorizationView()
            case .organization: OrganizationView()
            case .audit: AuditView()
            case .medicalInsurance: MedicalInsuranceView()
            case .profile: ProfileView()
            }
        }
        .navigationTitle(displayedRoute.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if displayedRoute != .home && displayedRoute != .profile && displayedRoute != .medicalInsurance {
                    Button { model.navigate(to: .home) } label: { Image(systemName: "arrow.left") }
                        .accessibilityLabel("返回工作台")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { confirmLogout = true } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
                .accessibilityLabel("退出登录")
            }
        }
    }
}

private struct ModuleGridView: View {
    @EnvironmentObject private var model: AppModel

    private var modules: [(AppRoute, String, String)] {
        let codes = Set(model.bootstrap?.activeModules.map(\.code) ?? [])
        return [
            (codes.contains("system.accounts") ? .accounts : nil, "账户管理", "person.2"),
            (codes.contains("system.graduates") ? .graduates : nil, "毕业生管理", "graduationcap"),
            (codes.contains("system.authorization") ? .authorization : nil, "身份与权限", "checkmark.shield"),
            (codes.contains("system.organization") ? .organization : nil, "学校与班级", "point.3.connected.trianglepath.dotted"),
            (codes.contains("system.audit") ? .audit : nil, "操作记录", "doc.text.magnifyingglass"),
            (codes.contains("medical-insurance") ? .medicalInsurance : nil, "医保信息系统", "cross.case")
        ].compactMap { route, title, symbol in route.map { ($0, title, symbol) } }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("工作台").font(.largeTitle.bold())
                if modules.isEmpty {
                    EmptyWorkspaceView()
                } else {
                    Text("已授权模块").foregroundStyle(.secondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                        ForEach(modules, id: \.0) { route, title, symbol in
                            Button {
                                if route == .medicalInsurance { model.openMedicalInsurance() } else { model.navigate(to: route) }
                            } label: {
                                VStack(alignment: .leading, spacing: 12) {
                                    Image(systemName: symbol).font(.title2).foregroundStyle(ShtongColor.primary)
                                    Text(title).font(.headline).foregroundStyle(ShtongColor.foreground)
                                }
                                .frame(maxWidth: .infinity, minHeight: 124, alignment: .leading)
                                .padding(16)
                                .background(ShtongColor.muted.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("打开\(title)")
                        }
                    }
                }
            }
            .padding(24)
        }
        .background(ShtongColor.background)
    }
}

private struct MedicalInsuranceView: View {
    @EnvironmentObject private var model: AppModel
    @State private var canGoBack = false
    @State private var loading = true
    @State private var leaving = false
    @State private var moduleWebView: WKWebView?

    var body: some View {
        Group {
            if let url = model.medicalInsuranceURL {
                VStack(spacing: 0) {
                    HStack {
                        Button { moduleWebView?.evaluateJavaScript("(function(){if(window.YibaoUI&&typeof window.YibaoUI.toggleSidebar==='function'){return window.YibaoUI.toggleSidebar();}var button=document.querySelector('.mobile-nav-toggle');if(button){button.click();return true;}return false;})()", completionHandler: nil) } label: { Image(systemName: "line.3.horizontal") }
                            .accessibilityLabel("打开医保模块菜单")
                            .disabled(leaving || loading)
                        Button { leaveModule() } label: { Label(leaving ? "正在返回" : "返回工作台", systemImage: "arrow.left") }
                        .disabled(model.busy || leaving)
                        Spacer()
                        if canGoBack { Button { moduleWebView?.goBack() } label: { Image(systemName: "chevron.left") }.accessibilityLabel("后退") }
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 48)
                    ZStack {
                        MedicalModuleWebView(url: url, canGoBack: $canGoBack, loading: $loading, onReady: { moduleWebView = $0 }, onExit: { model.closeMedicalInsurance() })
                        if loading { ProgressView().tint(ShtongColor.primary) }
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    Image(systemName: "cross.case").font(.system(size: 30, weight: .semibold)).foregroundStyle(ShtongColor.primary)
                    Text("医保信息系统").font(.title2.weight(.bold))
                    Text("使用生化通当前登录身份进入医保信息系统。").foregroundStyle(.secondary)
                    FeedbackBanner(error: model.error, notice: model.notice)
                    Button { model.openMedicalInsurance() } label: { Label("进入医保信息系统", systemImage: "cross.case") }
                        .buttonStyle(.borderedProminent).disabled(model.busy).frame(minHeight: 44)
                }
                .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).background(ShtongColor.background)
            }
        }
    }

    private func leaveModule() {
        guard !leaving else { return }
        leaving = true
        guard let current = model.medicalInsuranceURL else { model.closeMedicalInsurance(); return }
        let port = current.port.map { ":\($0)" } ?? ""
        guard let origin = URL(string: "\(current.scheme ?? "http")://\(current.host ?? "")\(port)") else { model.closeMedicalInsurance(); return }
        var exitComponents = URLComponents(url: origin.appendingPathComponent("medical-insurance/exit/"), resolvingAgainstBaseURL: false)
        exitComponents?.queryItems = [URLQueryItem(name: "embedded", value: "1")]
        let exitURL = exitComponents?.url ?? origin.appendingPathComponent("medical-insurance/exit/")
        moduleWebView?.load(URLRequest(url: exitURL))
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { if leaving { model.closeMedicalInsurance() } }
    }
}

private struct MedicalModuleWebView: UIViewRepresentable {
    let url: URL
    @Binding var canGoBack: Bool
    @Binding var loading: Bool
    let onReady: (WKWebView) -> Void
    let onExit: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        view.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData))
        context.coordinator.observe(view)
        DispatchQueue.main.async { onReady(view) }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if view.url == nil { view.load(URLRequest(url: url)) }
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.stopLoading(); view.navigationDelegate = nil
        view.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: MedicalModuleWebView
        private var observation: NSKeyValueObservation?
        init(_ parent: MedicalModuleWebView) { self.parent = parent }
        func observe(_ view: WKWebView) { observation = view.observe(\.canGoBack, options: [.initial, .new]) { [weak self] view, _ in DispatchQueue.main.async { self?.parent.canGoBack = view.canGoBack } } }
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { parent.loading = true }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.loading = false
            webView.evaluateJavaScript("document.querySelectorAll('.mobile-nav-toggle').forEach(function(button){button.style.setProperty('display','none','important');});", completionHandler: nil)
            if webView.url?.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).hasSuffix("medical-insurance/exit") == true { parent.onExit() }
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.loading = false
            if webView.url?.path.contains("medical-insurance/exit") == true { parent.onExit() }
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.loading = false
            if webView.url?.path.contains("medical-insurance/exit") == true { parent.onExit() }
        }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let target = action.request.url, let base = parent.url else { decisionHandler(.cancel); return }
            let sameOrigin = target.scheme == base.scheme && target.host == base.host && target.port == base.port
            decisionHandler(sameOrigin ? .allow : .cancel)
        }
    }
}

private struct EmptyWorkspaceView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "rectangle.grid.2x2")
                .font(.system(size: 38))
                .foregroundStyle(ShtongColor.primary)
            Text("当前没有已开放的业务模块")
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text("可在个人中心维护资料、修改密码或退出。")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button { model.navigate(to: .profile) } label: {
                Label("打开个人中心", systemImage: "person.crop.circle")
            }
            .buttonStyle(.bordered)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ShtongColor.background)
    }
}
