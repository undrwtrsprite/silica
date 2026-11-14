import SwiftUI

struct WorldsView: View {
    @EnvironmentObject private var state: AppState
    @Local private var confirmDelete: WorldInfo?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top) {
                PageHeader(title: "Worlds", subtitle: "Plain Beta 1.7.3 worlds, compatible with the original game and servers.")
                Spacer()
                Button { state.importWorld() } label: { Label("Import", systemImage: "square.and.arrow.down") }.buttonStyle(SecondaryButtonStyle())
                Button { state.refreshWorlds() } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(SecondaryButtonStyle()).help("Refresh")
            }
            if state.worlds.isEmpty {
                VStack(spacing: 12) {
                    WorldThumb(name: "empty").frame(width: 84, height: 84)
                    Text("No worlds yet").font(.system(size: 18, weight: .semibold, design: .rounded))
                    Text("Start the game and create one, or import a world .zip or folder.").font(.system(size: 13)).foregroundStyle(.secondary)
                    Button { state.importWorld() } label: { Label("Import a world", systemImage: "square.and.arrow.down") }.buttonStyle(PrimaryButtonStyle())
                }
                .frame(maxWidth: .infinity).padding(.vertical, 60).surface()
            } else {
                LazyVStack(spacing: 10) {
                    ForEach(state.worlds) { w in row(w) }
                }
            }
        }
        .onAppear { state.refreshWorlds() }
        .confirmationDialog("Move \(confirmDelete?.name ?? "") to the Trash?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
            Button("Move to Trash", role: .destructive) { if let w = confirmDelete { state.trash(w) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("You can restore it from the Trash. Back it up first if it matters.") }
    }

    private func row(_ w: WorldInfo) -> some View {
        HStack(spacing: 16) {
            WorldThumb(name: w.name).frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(w.name).font(.system(size: 15, weight: .semibold, design: .rounded))
                Text("\(ByteCountFormatter.string(fromByteCount: w.sizeBytes, countStyle: .file)) · \(w.modified.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "never played")")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Back up") { state.backup(w) }.buttonStyle(SecondaryButtonStyle())
            Button("Export") { state.export(w) }.buttonStyle(SecondaryButtonStyle())
            Button { confirmDelete = w } label: { Image(systemName: "trash") }.buttonStyle(SecondaryButtonStyle(tint: Theme.bad))
        }
        .padding(14).surface(radius: 18)
    }
}
