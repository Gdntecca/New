import SwiftUI
import SDCore

enum Theme {
    static let red = Color(red: 0.90, green: 0.16, blue: 0.14)
    static let redBright = Color(red: 1.0, green: 0.30, blue: 0.29)
    static let connColors: [Color] = [
        Color(red: 1.0, green: 0.29, blue: 0.29), Color(red: 1.0, green: 0.54, blue: 0.40), Color(red: 1.0, green: 0.72, blue: 0.30),
        Color(red: 1.0, green: 0.43, blue: 0.57), Color(red: 0.90, green: 0.45, blue: 0.45), Color(red: 1.0, green: 0.84, blue: 0.31),
        Color(red: 0.94, green: 0.38, blue: 0.57), Color(red: 1.0, green: 0.44, blue: 0.26),
    ]
    static func conn(_ i: Int) -> Color { connColors[((i - 1) % connColors.count + connColors.count) % connColors.count] }

    static func background(_ scheme: ColorScheme) -> Color { scheme == .dark ? .black : Color(white: 0.97) }
    static func card(_ scheme: ColorScheme) -> Color { scheme == .dark ? Color(white: 0.09) : .white }
    static func track(_ scheme: ColorScheme) -> Color { scheme == .dark ? Color(white: 0.16) : Color(white: 0.90) }

    static func icon(for category: String) -> String {
        switch category {
        case "Video": return "film"
        case "Music": return "music.note"
        case "Documents": return "doc.text"
        case "Archives": return "archivebox"
        case "Programs": return "app.badge"
        case "Images": return "photo"
        default: return "doc"
        }
    }
}

func whenLabel(_ date: Date?) -> String {
    guard let d = date else { return "" }
    let tf = DateFormatter()
    tf.timeStyle = .short
    tf.dateStyle = .none
    let cal = Calendar.current
    if cal.isDateInToday(d) { return "Today \(tf.string(from: d))" }
    if cal.isDateInYesterday(d) { return "Yesterday \(tf.string(from: d))" }
    let df = DateFormatter()
    df.setLocalizedDateFormatFromTemplate("MMM d")
    return "\(df.string(from: d)) \(tf.string(from: d))"
}

/// Segmented bar: each connection's piece in its own colour. Indeterminate when size is unknown.
struct SegmentedBar: View {
    var progress: Double
    var total: Int64
    var pieces: [LiveStats.Piece]?
    var indeterminate: Bool
    var height: CGFloat = 5
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.track(scheme))
                if indeterminate {
                    IndeterminateStripe(reduceMotion: reduceMotion).clipShape(Capsule())
                } else if let pieces = pieces, !pieces.isEmpty, total > 0 {
                    Canvas { ctx, size in
                        for p in pieces {
                            let x = CGFloat(Double(p.start) / Double(total)) * size.width
                            let w = CGFloat(Double(p.downloaded) / Double(total)) * size.width
                            ctx.fill(Path(CGRect(x: x, y: 0, width: max(w, 0), height: size.height)),
                                     with: .color(p.active ? Theme.conn(p.conn) : Theme.red))
                        }
                    }.clipShape(Capsule())
                } else {
                    Capsule().fill(Theme.red)
                        .frame(width: geo.size.width * CGFloat(min(max(progress, 0), 1)))
                        .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: progress)
                }
            }
        }
        .frame(height: height)
    }
}

private struct IndeterminateStripe: View {
    var reduceMotion: Bool
    @State private var phase: CGFloat = -0.4
    var body: some View {
        GeometryReader { geo in
            Capsule().fill(Theme.red)
                .frame(width: geo.size.width * 0.35)
                .offset(x: geo.size.width * phase)
                .onAppear {
                    guard !reduceMotion else { phase = 0.3; return }
                    withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { phase = 1.0 }
                }
        }
    }
}

/// Piece map: a grid of small squares, each a chunk of the file, filled as it downloads.
/// One Canvas, redrawn at most ~4×/s (the live stats tick rate).
struct PieceMap: View {
    var total: Int64
    var downloaded: Int64
    var pieces: [LiveStats.Piece]?
    var completed: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { ctx, size in
            let cell: CGFloat = 9, gap: CGFloat = 2
            let cols = max(1, Int((size.width + gap) / (cell + gap)))
            let rows = max(1, Int((size.height + gap) / (cell + gap)))
            let count = cols * rows
            var fill = [Double](repeating: 0, count: count)
            var color = [Int](repeating: 0, count: count)
            if completed {
                fill = [Double](repeating: 1, count: count)
            } else if total > 0 {
                let chunk = Double(total) / Double(count)
                if let pieces = pieces, !pieces.isEmpty {
                    for p in pieces where p.downloaded > 0 {
                        let a = Double(p.start), b = Double(p.start + p.downloaded)
                        let first = min(max(Int(a / chunk), 0), count - 1)
                        let last = min(max(Int((b - 1) / chunk), 0), count - 1)
                        if first > last { continue }
                        for i in first...last {
                            let cs = Double(i) * chunk, ce = cs + chunk
                            let ov = max(min(ce, b) - max(cs, a), 0)
                            fill[i] = min(1, fill[i] + ov / chunk)
                            if p.active { color[i] = p.conn }
                        }
                    }
                } else {
                    let f = Double(downloaded) / Double(total) * Double(count)
                    for i in 0..<count { fill[i] = min(max(f - Double(i), 0), 1) }
                }
            }
            for i in 0..<count {
                let r = CGRect(x: CGFloat(i % cols) * (cell + gap), y: CGFloat(i / cols) * (cell + gap), width: cell, height: cell)
                let path = Path(roundedRect: r, cornerRadius: 2)
                ctx.fill(path, with: .color(Theme.track(scheme)))
                if fill[i] > 0 {
                    let c = color[i] > 0 ? Theme.conn(color[i]) : Theme.red
                    ctx.fill(path, with: .color(c.opacity(fill[i] >= 0.999 ? 1 : 0.35 + 0.65 * fill[i])))
                }
            }
        }
        .frame(height: 140)
    }
}

/// Live speed graph (last ~90 s).
struct SpeedGraph: View {
    var history: [Double]
    var body: some View {
        Canvas { ctx, size in
            for i in 1...3 {
                let y = size.height * CGFloat(i) / 4
                ctx.stroke(Path { p in p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y)) },
                           with: .color(.gray.opacity(0.2)), lineWidth: 1)
            }
            guard history.count >= 2 else { return }
            let maxV = max(history.max() ?? 1, 1) * 1.15
            let step = size.width / CGFloat(history.count - 1)
            var line = Path()
            for (i, v) in history.enumerated() {
                let pt = CGPoint(x: CGFloat(i) * step, y: size.height - CGFloat(v / maxV) * size.height)
                if i == 0 { line.move(to: pt) } else { line.addLine(to: pt) }
            }
            var area = line
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            area.addLine(to: CGPoint(x: 0, y: size.height))
            area.closeSubpath()
            ctx.fill(area, with: .linearGradient(Gradient(colors: [Theme.red.opacity(0.35), .clear]),
                                                 startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            ctx.stroke(line, with: .color(Theme.red), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
        }
        .frame(height: 120)
    }
}

/// The crest logo drawn in SwiftUI (used on the Credits screen).
struct CrestLogo: View {
    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height) / 108
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s, y: y * s) }
            var outer = Path()
            outer.move(to: p(28, 22)); outer.addLine(to: p(80, 22))
            outer.addQuadCurve(to: p(85, 27), control: p(85, 22)); outer.addLine(to: p(85, 52))
            outer.addCurve(to: p(54, 91), control1: p(85, 70), control2: p(71, 82))
            outer.addCurve(to: p(23, 52), control1: p(37, 82), control2: p(23, 70))
            outer.addLine(to: p(23, 27)); outer.addQuadCurve(to: p(28, 22), control: p(23, 22)); outer.closeSubpath()
            var inner = Path()
            inner.move(to: p(32, 28)); inner.addLine(to: p(76, 28))
            inner.addQuadCurve(to: p(79, 31), control: p(79, 28)); inner.addLine(to: p(79, 52))
            inner.addCurve(to: p(54, 84), control1: p(79, 66), control2: p(68, 76))
            inner.addCurve(to: p(29, 52), control1: p(40, 76), control2: p(29, 66))
            inner.addLine(to: p(29, 31)); inner.addQuadCurve(to: p(32, 28), control: p(29, 28)); inner.closeSubpath()
            ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: size.width * 0.22), with: .color(.black))
            ctx.fill(outer, with: .color(Theme.red))
            ctx.fill(inner, with: .color(.black))
            var sPath = Path()
            let pts: [(CGFloat, CGFloat)] = [(66, 37), (45, 37), (39.5, 42.5), (39.5, 46), (44.5, 51), (63.5, 51), (68.5, 56), (68.5, 60.5), (63, 66), (54, 66), (54, 69)]
            sPath.move(to: p(pts[0].0, pts[0].1))
            for q in pts.dropFirst() { sPath.addLine(to: p(q.0, q.1)) }
            ctx.stroke(sPath, with: .color(Theme.red), style: StrokeStyle(lineWidth: 6.5 * s, lineCap: .square, lineJoin: .miter))
            var arrow = Path()
            arrow.move(to: p(45.5, 68)); arrow.addLine(to: p(62.5, 68)); arrow.addLine(to: p(54, 78.5)); arrow.closeSubpath()
            ctx.fill(arrow, with: .color(Theme.red))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}
