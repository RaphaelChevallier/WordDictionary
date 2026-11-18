import SwiftUI
import UIKit

enum AppPalette {
    static let surface = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 21/255, green: 21/255, blue: 21/255, alpha: 1) : UIColor(red: 250/255, green: 250/255, blue: 247/255, alpha: 1) })
    static let foreground = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 242/255, green: 242/255, blue: 237/255, alpha: 1) : UIColor(red: 27/255, green: 27/255, blue: 27/255, alpha: 1) })
    static let secondary = foreground.opacity(0.68)
    static let outline = foreground.opacity(0.18)
    static let muted = foreground.opacity(0.075)

    static func accent(_ value: AppAccent, dark: Bool) -> Color {
        switch value {
        case .monochrome: return foreground
        case .blue: return dark ? Color(red: 0.66, green: 0.76, blue: 1) : Color(red: 0.16, green: 0.35, blue: 0.66)
        case .green: return dark ? Color(red: 0.63, green: 0.84, blue: 0.71) : Color(red: 0.16, green: 0.39, blue: 0.27)
        case .rose: return dark ? Color(red: 0.94, green: 0.71, blue: 0.78) : Color(red: 0.60, green: 0.22, blue: 0.33)
        }
    }
}

struct ReadingFont: ViewModifier {
    @Environment(AppModel.self) private var model
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    let design: Font.Design

    init(size: CGFloat, weight: Font.Weight, design: Font.Design) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: .body)
        self.weight = weight
        self.design = design
    }
    func body(content: Content) -> some View {
        content.font(.system(size: size * CGFloat(model.settings.readingSize.scale), weight: weight, design: design))
    }
}

extension View {
    func readingFont(_ size: CGFloat = 18, weight: Font.Weight = .regular, design: Font.Design = .default) -> some View {
        modifier(ReadingFont(size: size, weight: weight, design: design))
    }
    func wordActionTarget() -> some View { frame(minWidth: 48, minHeight: 48).contentShape(Rectangle()) }
}

extension AppTheme {
    var colorScheme: ColorScheme? {
        switch self { case .system: return nil; case .light: return .light; case .dark: return .dark }
    }
}

struct AppSectionTitle: View {
    let title: String
    var body: some View { Text(title).readingFont(14, weight: .semibold).foregroundStyle(AppPalette.secondary) }
}
