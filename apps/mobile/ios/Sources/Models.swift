import Foundation

enum Contract {
    static let version = "account-access-baseline.v1"
    static let systemModules = ["system.accounts", "system.graduates", "system.authorization", "system.organization", "system.audit"]
    static let roles = ["USER", "TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN", "SYSTEM_ADMIN"]
    static let initialRoles = ["USER", "TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN"]
}

enum AppPhase: Equatable { case checking, login, passwordChange, profileCompletion, workspace }
enum AppRoute: String, CaseIterable, Hashable, Identifiable {
    case home, accounts, graduates, authorization, organization, audit, medicalInsurance, profile
    var id: String { rawValue }
    var title: String {
        switch self {
        case .home: "工作台"
        case .accounts: "账户管理"
        case .graduates: "毕业生管理"
        case .authorization: "身份与权限"
        case .organization: "学校与班级"
        case .audit: "操作记录"
        case .medicalInsurance: "医保信息系统"
        case .profile: "个人中心"
        }
    }
    var symbol: String {
        switch self {
        case .home: "rectangle.grid.2x2"
        case .accounts: "person.2"
        case .graduates: "graduationcap"
        case .authorization: "checkmark.shield"
        case .organization: "point.3.connected.trianglepath.dotted"
        case .audit: "doc.text.magnifyingglass"
        case .medicalInsurance: "cross.case"
        case .profile: "person.crop.circle"
        }
    }
}

struct LoginRequest: Codable { let loginName: String; let password: String; let channel = "IOS" }
struct ContextRequest: Codable { let workspace: String }
struct RefreshRequest: Codable { let channel = "IOS"; let refreshToken: String }
struct PasswordChangeRequest: Codable { let currentPassword: String; let newPassword: String }
struct LoginResponse: Codable { let status: String; let challengeId: String?; let expiresAt: String?; let allowedActions: [String]?; let session: SessionEstablished? }
struct SessionEstablished: Codable { let sessionId: String; let context: SessionContext; let tokenDelivery: String; let mobileTokens: MobileTokens? }
struct SessionContext: Codable { let id: String; let workspace: String; let assuranceLevel: String; let expiresAt: String }
struct MobileTokens: Codable { let tokenType: String; let accessToken: String; let refreshToken: String; let accessTokenExpiresAt: String; let refreshTokenExpiresAt: String }
struct Bootstrap: Codable { let contractVersion: String; let authorizationVersion: Int64; let profileCompletionRequired: Bool; let activeContext: SessionContext?; let activeModules: [ActiveModule]; let shellActions: [String]; let availableWorkspaces: [AvailableWorkspace] }
struct ActiveModule: Codable, Identifiable { var id: String { code }; let code: String; let routeKey: String; let order: Int; let externalSystem: String?; let sso: String? }
struct AvailableWorkspace: Codable { let code: String; let requiresStepUp: Bool }
struct ProfileOrganizationItem: Codable { let type: String; let code: String; let name: String }
struct ProfileCounselor: Codable { let displayName: String; let personNo: String }
struct ProfileScope: Codable { let type: String; let name: String; let path: String }
struct ProfileManagedScope: Codable { let roleCode: String; let scopes: [ProfileScope] }
struct Profile: Codable { let accountId: String; let displayName: String; let personNo: String; let gender: String?; let ethnicity: String?; let age: Int?; let roleCodes: [String]; let organizationPath: [ProfileOrganizationItem]; let counselors: [ProfileCounselor]; let managedScopes: [ProfileManagedScope]; let profileCompletionRequired: Bool; let idCardNumberMasked: String?; let mobileNumberMasked: String?; let currentAddressMasked: String?; let householdAddressMasked: String?; let version: Int64 }
struct AdministrativeDivision: Codable, Identifiable { var id: String { code }; let code: String; let name: String }
struct ProfileAddressInput: Codable { let provinceCode: String; let cityCode: String; let districtCode: String; let detail: String }
struct ProfileAddressDetail: Codable { let provinceCode: String; let provinceName: String; let cityCode: String; let cityName: String; let districtCode: String; let districtName: String; let detail: String }
struct ProfileCompletionRequest: Codable { let idCardNumber: String; let mobileNumber: String; let ethnicity: String; let currentAddress: ProfileAddressInput; let householdAddress: ProfileAddressInput }
struct ProfileCompletionResponse: Codable { let profile: Profile; let session: SessionEstablished }
struct SensitiveFieldRevealRequest: Codable { let password: String }
struct SensitiveProfileFieldResponse: Codable { let field: String; let value: String }

struct AccountSummary: Codable, Identifiable {
    let id: String
    let loginName: String
    let displayName: String
    let personNo: String
    let gender: String?
    var graduatedAt: String? = nil
    var graduatedReason: String? = nil
    let lifecycleStatus: String
    let securityStatus: String
    let roleCodes: [String]
    let passwordChangeRequired: Bool
    let version: Int64
}
struct AccountPage: Codable { let items: [AccountSummary]; let nextCursor: String?; let hasMore: Bool }
struct AccountDetail: Codable, Identifiable {
    let id: String
    let loginName: String
    let displayName: String
    let personNo: String
    let gender: String?
    var ethnicity: String? = nil
    var age: Int? = nil
    var graduatedAt: String? = nil
    var graduatedReason: String? = nil
    let lifecycleStatus: String
    let securityStatus: String
    let roleCodes: [String]
    let passwordChangeRequired: Bool
    let lastLoginAt: String?
    let version: Int64
    let classId: String?
    let organizationPath: [ProfileOrganizationItem]
    let idCardNumber: String?
    let mobileNumber: String?
    let currentAddress: ProfileAddressDetail?
    let householdAddress: ProfileAddressDetail?
    let profileCompleted: Bool
    let createdAt: String
    let updatedAt: String

    var summary: AccountSummary {
        AccountSummary(
            id: id,
            loginName: loginName,
            displayName: displayName,
            personNo: personNo,
            gender: gender,
            graduatedAt: graduatedAt,
            graduatedReason: graduatedReason,
            lifecycleStatus: lifecycleStatus,
            securityStatus: securityStatus,
            roleCodes: roleCodes,
            passwordChangeRequired: passwordChangeRequired,
            version: version
        )
    }
}
struct ScopeCeilingRequest: Codable { let orgUnitId: String }
struct InitialRoleRequest: Codable { let roleCode: String; let scopeCeilings: [ScopeCeilingRequest] }
struct AccountCreateRequest: Codable {
    let loginName: String
    let displayName: String
    let personNo: String
    let gender: String?
    let classId: String?
    let roleAssignments: [InitialRoleRequest]
    let reason: String
}
struct AccountUpdateRequest: Codable {
    let loginName: String
    let displayName: String
    let gender: String?
    let idCardNumber: String?
    let mobileNumber: String?
    let currentAddress: ProfileAddressInput?
    let householdAddress: ProfileAddressInput?
    let expectedVersion: Int64
    let reason: String
}
struct EnrollmentChangeRequest: Codable {
    let classId: String
    let expectedVersion: Int64
    let reason: String
}
struct OneTimeCredential: Codable { let temporaryPassword: String }
struct AccountCreateResponse: Codable { let account: AccountDetail; let credentialDeliveryStatus: String; let credential: OneTimeCredential? }
struct BulkCommandRequest: Codable { let accountIds: [String]; let expectedCount: Int; let reason: String }

struct OrgUnit: Codable, Identifiable { let id: String; let type: String; let parentId: String?; let code: String; let name: String; let status: String; let version: Int64 }
struct OrgWriteRequest: Codable { let type: String; let parentId: String?; let code: String; let name: String; let expectedVersion: Int64; let reason: String }
struct RoleAssignment: Codable, Identifiable { let id: String; let roleCode: String; let status: String; let scopeCeilings: [OrgUnit]; let validFrom: String; let validTo: String?; let version: Int64 }
struct RoleAssignmentRequest: Codable { let roleCode: String; let scopeCeilingOrgUnitIds: [String]; let validFrom: String; let validTo: String?; let reason: String }
struct ReasonRequest: Codable { let reason: String }

struct AuditEvent: Codable, Identifiable {
    var id: String { eventId }
    let eventId: String
    let eventCode: String
    let occurredAt: String
    let actorAccountId: String?
    let authorizationDecision: String
    let outcome: String
    let requestId: String
    let subjectCount: Int?
}
struct AuditEventDetail: Codable, Identifiable {
    var id: String { eventId }
    let eventId: String
    let eventCode: String
    let occurredAt: String
    let actorAccountId: String?
    let authorizationDecision: String
    let outcome: String
    let requestId: String
    let subjectCount: Int?
    let workspace: String?
    let channel: String?
    let reasonCode: String?
    let subjects: [String]?
    let resources: [JSONObject]?
    let changes: [JSONObject]?
    let authorizationEvidence: [JSONObject]?
}
struct ImportIssue: Codable, Identifiable { var id: String { "\(sheet)-\(row)-\(field)-\(code)" }; let sheet: String; let row: Int; let field: String; let code: String; let messageKey: String? }
struct ImportJob: Codable, Identifiable { let id: String; let state: String; let templateVersion: String; let organizationRevision: Int64; let rowCount: Int; let errorCount: Int }
struct ImportCommitRequest: Codable { let validatedRevision: Int64; let expectedAccountCount: Int; let reason: String }
struct DownloadTicket: Codable { let url: String; let expiresAt: String; let singleUse: Bool }
struct MedicalInsuranceTicket: Codable { let moduleCode: String; let audience: String; var redirectUrl: String; let expiresAt: String; let singleUse: Bool }
struct JobAccepted: Codable { let jobId: String; let state: String; let controlRequestId: String? }
struct JobStatus: Codable { let id: String; let state: String; let resultCount: Int?; let failureCode: String?; let createdAt: String; let completedAt: String? }
struct ControlRequest: Codable, Identifiable { let id: String; let commandType: String; let reason: String; let state: String; let version: Int64 }
struct ControlDecisionRequest: Codable { let expectedVersion: Int64; let reason: String }
struct Page<T: Codable>: Codable { let items: [T]; let nextCursor: String?; let hasMore: Bool }

struct APIErrorBody: Codable, Error { let code: String; let messageKey: String?; let requestId: String? }
struct APITransportError: Error { let status: Int; let body: APIErrorBody }

enum JSONValue: Codable, Hashable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode(Double.self) { self = .number(value) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([String: JSONValue].self) { self = .object(value) }
        else { self = .array(try container.decode([JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var string: String? { if case .string(let value) = self { value } else { nil } }
    var int64: Int64? { if case .number(let value) = self { Int64(value) } else { nil } }
    var object: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }
}
typealias JSONObject = [String: JSONValue]

struct EmptyResponse: Codable {}
