import OutboundSalesNative
import SwiftUI

@main
struct OutboundSalesMacApp: App {
    var body: some Scene {
        WindowGroup {
            OutboundSalesRootView()
                .frame(minWidth: 980, minHeight: 680)
        }
        .defaultSize(width: 1280, height: 820)
        .windowResizability(.contentMinSize)
    }
}
