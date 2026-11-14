import SwiftUI

/// The setup window's frame: a rounded card with a rail on the left (the step list) and the current step on the right.
struct LauncherPanel<Rail: View, Content: View>: View {
    let railWidth: CGFloat
    @ViewBuilder var rail: Rail
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 0) {
            rail
                .frame(width: railWidth)
                .frame(maxHeight: .infinity, alignment: .topLeading)
            Rectangle().fill(Theme.stroke).frame(width: 1)
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Theme.stroke).allowsHitTesting(false) }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

/// The app icon with a title and the supported version, at the top of the setup rail.
struct LauncherBrand: View {
    let title: String

    var body: some View {
        HStack(spacing: 11) {
            Image(nsImage: Theme.appMark)
                .resizable().frame(width: 42, height: 42).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 18, weight: .bold, design: .rounded))
                Text("Beta 1.7.3 for Apple Silicon").font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
    }
}
