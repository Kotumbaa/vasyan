import Dialog from "@/shared/ui/Dialog";
import { DialogActions, DialogButton, DialogHeader } from "@/shared/ui";
import { useTranslation } from "@/languages";
import { useUpdaterStore } from "../store/updaterStore";

export default function UpdateModal() {
  const { t } = useTranslation();
  const isDialogOpen = useUpdaterStore((state) => state.isDialogOpen);
  const updateInfo = useUpdaterStore((state) => state.updateInfo);
  const skipUpdate = useUpdaterStore((state) => state.skipUpdate);
  const startDownload = useUpdaterStore((state) => state.startDownload);

  if (!updateInfo) return null;

  return (
    <Dialog
      open={isDialogOpen}
      onOpenChange={(open) => {
        if (!open) skipUpdate();
      }}
      maxWidth={360}
    >
      <div className="flex flex-col gap-[16px] px-[20px] pb-[20px]">
        <DialogHeader
          title={t("updater.modal_title")}
          description={t("updater.modal_desc", { version: updateInfo.version })}
        />

        <DialogActions>
          <DialogButton onClick={skipUpdate}>
            {t("updater.skip_button") || "Skip"}
          </DialogButton>
          <DialogButton variant="primary" onClick={() => void startDownload()}>
            {t("updater.update_button") || "Update"}
          </DialogButton>
        </DialogActions>
      </div>
    </Dialog>
  );
}
