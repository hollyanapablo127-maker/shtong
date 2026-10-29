import SwiftUI

@main
struct ShtongApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                    .environmentObject(model)
                    .tint(ShtongColor.primary)
                    .privacySensitive()

                if scenePhase != .active && [.workspace, .profileCompletion, .passwordChange].contains(model.phase) {
                    PrivacyCover()
                        .transition(.opacity)
                        .zIndex(100)
                }
            }
            .background(ShtongColor.background)
            .onOpenURL(perform: model.handleDeepLink)
        }
    }
}

private struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch model.phase {
            case .checking:
                VStack(spacing: 12) {
                    ProgressView()
                    Text("正在验证会话").foregroundStyle(.secondary)
                }
            case .login:
                NavigationStack { LoginView() }
            case .passwordChange:
                NavigationStack { PasswordChangeView(forced: true) }
            case .profileCompletion:
                NavigationStack { ProfileCompletionView() }
            case .workspace:
                WorkspaceView()
                    .id(model.bootstrap?.activeContext?.id)
            }
            if model.busy { BusyOverlay() }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: model.phase)
    }
}

private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            ShtongColor.background.ignoresSafeArea()
            VStack(spacing: 10) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(ShtongColor.primary)
                Text("生化通").font(.title2.weight(.bold))
                Text("已隐藏受保护内容").font(.callout).foregroundStyle(.secondary)
            }
        }
        .accessibilityLabel("应用已进入后台，受保护内容已隐藏")
    }
}
