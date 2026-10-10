import SwiftUI

// =============================================================================
// DylanTokens — Swift mirror of shared design tokens (plan §11.2).
// Light/dark pairs adapt via dynamic provider colors; parity goldens vs
// tokens.json land with the M4 golden suites.
// =============================================================================

// Nothing OS — pure black #000000, red #FF3030 signal, 1px #2E2E2E, no gradients
enum DylanTokens {
    static let primaryLight = Color(hex: 0xFF3030)
    static let primaryDark = Color(hex: 0xFF3030)
    static let backgroundLight = Color(hex: 0x000000)
    static let backgroundDark = Color(hex: 0x000000)
    static let surfaceLight = Color(hex: 0x111111)
    static let surfaceDark = Color(hex: 0x111111)
    static let surfaceVariantLight = Color(hex: 0x1A1A1A)
    static let surfaceVariantDark = Color(hex: 0x1A1A1A)
    static let textPrimaryLight = Color(hex: 0xFFFFFF)
    static let textPrimaryDark = Color(hex: 0xFFFFFF)
    static let textSecondaryLight = Color(hex: 0x8A8A8A)
    static let textSecondaryDark = Color(hex: 0x8A8A8A)
    static let dividerLight = Color(hex: 0x2E2E2E)
    static let dividerDark = Color(hex: 0x2E2E2E)
    static let errorLight = Color(hex: 0xFF3030)
    static let errorDark = Color(hex: 0xFF3030)
    static let gradientStop = Color(hex: 0x000000)

    static var primary: Color { color(light: primaryLight, dark: primaryDark) }
    static var background: Color { color(light: backgroundLight, dark: backgroundDark) }
    static var surface: Color { color(light: surfaceLight, dark: surfaceDark) }
    static var surfaceVariant: Color { color(light: surfaceVariantLight, dark: surfaceVariantDark) }
    static var textPrimary: Color { color(light: textPrimaryLight, dark: textPrimaryDark) }
    static var textSecondary: Color { color(light: textSecondaryLight, dark: textSecondaryDark) }
    static var divider: Color { color(light: dividerLight, dark: dividerDark) }
    static var error: Color { color(light: errorLight, dark: errorDark) }

    private static func color(light: Color, dark: Color) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(dark) : UIColor(light)
        })
    }

    // Spacing scale (dp ≈ pt)
    static let s4: CGFloat = 4
    static let s6: CGFloat = 6
    static let s8: CGFloat = 8
    static let s12: CGFloat = 12
    static let s16: CGFloat = 16
    static let s24: CGFloat = 24

    // Radii — Nothing: 0 for cards, 6 for buttons
    static let radiusSm: CGFloat = 0
    static let radiusMd: CGFloat = 6
    static let radiusLg: CGFloat = 0
    static let radiusXl: CGFloat = 0

    // Motion — Nothing: no spring, cubic-bezier only
    static let fastMs: Double = 150
    static let normalMs: Double = 250
    static let springSec: TimeInterval = 0.3
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}

// Type scale — Nothing: NDot dot-matrix + NType mono substitute (Space Mono / JetBrains Mono tracking)
extension Font {
    static let dylDisplayLarge = Font.system(size: 28, weight: .black).monospaced()
    static let dylDisplayMedium = Font.system(size: 22, weight: .bold).monospaced()
    static let dylTitleLarge = Font.system(size: 18, weight: .bold).monospaced()
    static let dylTitleMedium = Font.system(size: 13, weight: .medium).monospaced()
    static let dylBodyLarge = Font.system(size: 15, weight: .regular)
    static let dylBodyMedium = Font.system(size: 13, weight: .regular)
    static let dylLabelSmall = Font.system(size: 11, weight: .medium).monospaced()
}
