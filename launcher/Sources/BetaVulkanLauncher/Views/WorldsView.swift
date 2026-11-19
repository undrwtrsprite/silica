import SwiftUI

/// Clean world saves view scoped to the currently active instance, with optional quick instance switching.
struct WorldsView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var instances: InstanceStore
    @Local private var confirmDelete: WorldInfo?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .bottom) {
                PageHeader(
                    eyebrow: "Saves",
                    title: "Worlds",
                    subtitle: "Worlds in \"\(state.activeInstance.name)\", stored in standard Golden Age format."
                )
                Spacer()
                HStack(spacing: 8) {
                    if instances.instances.count > 1 {
                        Menu {
                            ForEach(instances.instances) { item in
                                Button {
                                    state.selectInstance(item.id)
                                } label: {
                                    Label(item.name, systemImage: item.id == state.activeInstance.id ? "checkmark" : "square.stack.3d.up")
                                }
                            }
                        } label: {
                            Label(state.activeInstance.name, systemImage: "square.stack.3d.up")
                        }
                        .menuStyle(.button)
                        .buttonStyle(SecondaryButtonStyle())
                    }

                    Button { state.importWorld() } label: {
                        Label("Import", systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(SecondaryButtonStyle())

                    Button { state.refreshWorlds() } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .help("Refresh")
                }
            }

            if state.worlds.isEmpty {
                VStack(spacing: 12) {
                    WorldDitherMap(seedName: state.activeInstance.name)
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
                    Text("No worlds yet")
                        .font(.system(size: 17, weight: .semibold))
                    Text("Start \"\(state.activeInstance.name)\" to create one, or import a world .zip or folder.")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Button { state.importWorld() } label: {
                        Label("Import a world", systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 52)
                .surface()
            } else {
                LazyVStack(spacing: 10) {
                    ForEach(state.worlds) { w in row(w) }
                }
            }
        }
        .onAppear { state.refreshWorlds() }
        .confirmationDialog(
            "Move \(confirmDelete?.name ?? "") to the Trash?",
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
        ) {
            Button("Move to Trash", role: .destructive) {
                if let w = confirmDelete { state.trash(w) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You can restore it from the Trash. Back it up first if it matters.")
        }
    }

    private func row(_ w: WorldInfo) -> some View {
        HStack(spacing: 16) {
            WorldDitherMap(seedName: w.name)
                .frame(width: 54, height: 54)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            VStack(alignment: .leading, spacing: 3) {
                Text(w.name).font(.system(size: 15, weight: .semibold))
                Text("\(ByteCountFormatter.string(fromByteCount: w.sizeBytes, countStyle: .file)) · \(w.modified.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "never played")")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Back up") { state.backup(w) }.buttonStyle(SecondaryButtonStyle())
            Button("Export") { state.export(w) }.buttonStyle(SecondaryButtonStyle())
            Button { confirmDelete = w } label: { Image(systemName: "trash") }
                .buttonStyle(SecondaryButtonStyle(tint: Theme.bad))
        }
        .padding(14)
        .surface(radius: 16)
    }
}
