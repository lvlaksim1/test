import { KeyboardAvoidingView, Modal, Platform, Pressable, Text, TextInput, View } from "react-native";

import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";

export function SaveConfigurationModal({ visible, name, onNameChange, onCancel, onSave }: {
  visible: boolean;
  name: string;
  onNameChange: (value: string) => void;
  onCancel: () => void;
  onSave: () => void;
}) {
  const colors = useColors();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <KeyboardAvoidingView style={styles.modalBackdrop} behavior={Platform.OS === "ios" ? "padding" : undefined}>
        <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Text style={[styles.modalTitle, { color: colors.text }]}>Сохранить конфигурацию</Text>
          <Text style={[styles.modalHint, { color: colors.muted }]}>Введите название конфигурации</Text>
          <TextInput
            autoFocus
            selectTextOnFocus
            value={name}
            onChangeText={onNameChange}
            onSubmitEditing={onSave}
            returnKeyType="done"
            placeholder="Название"
            placeholderTextColor={colors.muted}
            style={[styles.modalInput, { backgroundColor: colors.background, borderColor: colors.border, color: colors.text }]}
          />
          <View style={styles.modalActions}>
            <Pressable onPress={onCancel} style={({ pressed }) => [styles.modalActionButton, pressed && styles.pressed]}>
              <Text style={[styles.modalCancelText, { color: colors.muted }]}>Отмена</Text>
            </Pressable>
            <Pressable onPress={onSave} style={({ pressed }) => [styles.modalActionButton, styles.modalSaveButton, { backgroundColor: colors.primary }, pressed && styles.pressed]}>
              <Text style={styles.modalSaveText}>Сохранить</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
