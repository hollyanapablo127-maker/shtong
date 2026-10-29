import Foundation
import Security

protocol TokenStore: AnyObject {
    func save(access:String,refresh:String) throws
    func access()->String?
    func refresh()->String?
    func clear()
}

final class KeychainStore: TokenStore {
    private let service="com.shtong.session"
    func save(access:String, refresh:String) throws { try set(access,key:"access");try set(refresh,key:"refresh") }
    func access()->String? { get("access") }
    func refresh()->String? { get("refresh") }
    func clear(){ delete("access");delete("refresh") }
    private func set(_ value:String,key:String) throws { delete(key);let data=Data(value.utf8);let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:key,kSecValueData as String:data,kSecAttrAccessible as String:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly];guard SecItemAdd(query as CFDictionary,nil)==errSecSuccess else{throw APIErrorBody(code:"KEYCHAIN_WRITE_FAILED",messageKey:nil,requestId:nil)} }
    private func get(_ key:String)->String? { var item:CFTypeRef?;let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:key,kSecReturnData as String:true,kSecMatchLimit as String:kSecMatchLimitOne];guard SecItemCopyMatching(query as CFDictionary,&item)==errSecSuccess,let data=item as? Data else{return nil};return String(data:data,encoding:.utf8) }
    private func delete(_ key:String){let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:key];SecItemDelete(query as CFDictionary)}
}
