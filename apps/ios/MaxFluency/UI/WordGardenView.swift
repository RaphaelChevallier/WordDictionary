import SwiftUI

struct WordGardenView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    let words: [WordEntry]
    let viewportHeight: CGFloat
    let viewportWidth: CGFloat
    let round: Int

    var body: some View {
        Group {
            if reduceMotion { measuredGarden }
            else {
                measuredGarden.keyframeAnimator(initialValue: Reveal(), trigger: round) { content, value in
                    content.opacity(0.55 + 0.45 * value.progress).offset(y: CGFloat(4 * (1 - value.progress)))
                } keyframes: { _ in
                    KeyframeTrack(\.progress) {
                        MoveKeyframe(0)
                        CubicKeyframe(1, duration: 0.22)
                    }
                }
            }
        }
        .frame(minHeight: viewportHeight)
        .accessibilityIdentifier("daily-grid")
    }

    private struct Reveal: Sendable { var progress = 1.0 }

    private var measuredGarden: some View {
            let scale = min(max(min(viewportWidth / 350, viewportHeight / 460), 0.85), 1.6)
            return GardenLayout(viewportHeight: viewportHeight) {
                ForEach(words.indices, id: \.self) { index in
                    let word = words[index]
                    let rotated = index % 5 == 3
                    let size = CGFloat([34, 20, 28, 18, 30, 22, 17, 26][index % 8]) * scale
                    Button { model.openWord(id: word.id) } label: {
                        GardenTileLayout(rotated: rotated) {
                            Text(word.word).readingFont(max(15, size), weight: index % 3 == 0 ? .bold : .regular, design: .serif)
                                .lineLimit(1).minimumScaleFactor(0.55).fixedSize(horizontal: false, vertical: true)
                                .rotationEffect(.degrees(rotated ? (index % 2 == 0 ? 90 : -90) : 0))
                        }
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(index % 5 == 0 ? AppPalette.accent(model.settings.accent, dark: scheme == .dark) : AppPalette.foreground)
                    .accessibilityLabel(word.word).accessibilityIdentifier("garden-word-\(word.id)")
                }
            }
    }
}

private struct GardenTileLayout: Layout {
    let rotated: Bool
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let label = subviews.first else { return .zero }
        let maximum = proposal.width ?? 350
        let size = label.sizeThatFits(ProposedViewSize(width: rotated ? max(440, maximum) : max(1, maximum - 8), height: nil))
        return CGSize(width: min(maximum, max(48, (rotated ? size.height : size.width) + 8)),
                      height: max(48, (rotated ? size.width : size.height) + 8))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let label = subviews.first else { return }
        label.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center,
                    proposal: ProposedViewSize(width: rotated ? bounds.height - 8 : bounds.width - 8, height: rotated ? bounds.width - 8 : bounds.height - 8))
    }
}

struct GardenLayout: Layout {
    struct Cache {
        var sizes: [CGSize] = []
        var positions: [CGPoint] = []
        var height: CGFloat = 0
        var width: CGFloat = 0
    }
    let viewportHeight: CGFloat
    func makeCache(subviews: Subviews) -> Cache { Cache() }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        let width = max(48, floor(proposal.width ?? 350))
        let sizes = subviews.map { item -> CGSize in
            let size = item.sizeThatFits(ProposedViewSize(width: width, height: nil))
            return CGSize(width: ceil(size.width), height: ceil(size.height))
        }
        var height = max(viewportHeight, sizes.map(\.height).max() ?? 0)
        let limit = max(height, 2 * sizes.reduce(0) { $0 + $1.height + 4 })
        var positions = Self.pack(sizes: sizes, width: width, height: height)
        while positions == nil && height <= limit {
            height += 48
            positions = Self.pack(sizes: sizes, width: width, height: height)
        }
        precondition(positions != nil, "Measured word garden could not be packed.")
        cache = Cache(sizes: sizes, positions: positions ?? [], height: height, width: width)
        return CGSize(width: width, height: height)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        if cache.positions.count != subviews.count || cache.width != floor(bounds.width) {
            _ = sizeThatFits(proposal: ProposedViewSize(width: bounds.width, height: nil), subviews: subviews, cache: &cache)
        }
        guard !cache.positions.isEmpty else { return }
        let top = cache.positions.map(\.y).min() ?? 0
        let bottom = cache.positions.indices.map { cache.positions[$0].y + cache.sizes[$0].height }.max() ?? 0
        let inset = min(8, (cache.height - (bottom - top)) / 2)
        let stretch = cache.positions.indices.filter { cache.positions[$0].y > top }.map {
            (cache.height - 2 * inset - cache.sizes[$0].height) / (cache.positions[$0].y - top)
        }.min()
        for index in subviews.indices {
            let size = cache.sizes[index]
            let y = stretch.map { inset + (cache.positions[index].y - top) * max(1, $0) } ?? (cache.height - size.height) / 2
            subviews[index].place(at: CGPoint(x: bounds.minX + cache.positions[index].x, y: bounds.minY + y),
                                 proposal: ProposedViewSize(size))
        }
    }
    static func pack(sizes: [CGSize], width: CGFloat, height: CGFloat) -> [CGPoint]? {
        var spaces = [CGRect(x: 0, y: 0, width: width, height: height)]
        var positions = Array(repeating: CGPoint.zero, count: sizes.count)
        let order = sizes.indices.sorted {
            let left = sizes[$0].width * sizes[$0].height, right = sizes[$1].width * sizes[$1].height
            return left == right ? $0 < $1 : left > right
        }
        for index in order {
            let size = sizes[index]
            let candidates = spaces.filter { $0.width >= size.width && $0.height >= size.height }.map { space in
                CGRect(x: min(max(floor((width - size.width) / 2), space.minX), space.maxX - size.width),
                       y: min(max(floor((height - size.height) / 2), space.minY), space.maxY - size.height),
                       width: size.width, height: size.height)
            }
            guard let placed = candidates.min(by: { left, right in
                let a = pow(left.minX + left.maxX - width, 2) + pow(left.minY + left.maxY - height, 2)
                let b = pow(right.minX + right.maxX - width, 2) + pow(right.minY + right.maxY - height, 2)
                return a < b
            }) else { return nil }
            positions[index] = placed.origin
            let occupied = placed.insetBy(dx: -4, dy: -4)
            var remaining: [CGRect] = []
            for space in spaces {
                if !space.intersects(occupied) { remaining.append(space); continue }
                if occupied.minX > space.minX { remaining.append(CGRect(x: space.minX, y: space.minY, width: occupied.minX - space.minX, height: space.height)) }
                if occupied.maxX < space.maxX { remaining.append(CGRect(x: occupied.maxX, y: space.minY, width: space.maxX - occupied.maxX, height: space.height)) }
                if occupied.minY > space.minY { remaining.append(CGRect(x: space.minX, y: space.minY, width: space.width, height: occupied.minY - space.minY)) }
                if occupied.maxY < space.maxY { remaining.append(CGRect(x: space.minX, y: occupied.maxY, width: space.width, height: space.maxY - occupied.maxY)) }
            }
            let unique = remaining.reduce(into: [CGRect]()) { if !$0.contains($1) { $0.append($1) } }
            spaces = unique.filter { candidate in !unique.contains { $0 != candidate && $0.contains(candidate) } }
        }
        return positions
    }
}
