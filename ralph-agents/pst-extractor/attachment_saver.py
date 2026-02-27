import os
import shutil
import hashlib
from loguru import logger
import pypff


def sanitize_filename(filename):
    """Sanitize filename to remove invalid characters."""
    # Remove or replace invalid characters
    invalid_chars = '<>:"/\\|?*'
    sanitized = filename
    for char in invalid_chars:
        sanitized = sanitized.replace(char, '_')
    return sanitized


def calculate_sha256(file_path):
    """Calculate SHA256 hash of a file."""
    sha256_hash = hashlib.sha256()
    try:
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                sha256_hash.update(chunk)
        return sha256_hash.hexdigest()
    except Exception as e:
        logger.error(f"SHA256 kalkuláció hiba: {file_path} - {str(e)}")
        return None


def save_attachments(pst_path, extracted_path, db):
    """Save attachments from a PST file to the extracted directory."""
    logger.info(f"Csatolmányok mentése: {pst_path}")

    try:
        # Create base directory for this PST file
        pst_stem = os.path.splitext(os.path.basename(pst_path))[0]
        pst_extract_path = os.path.join(extracted_path, pst_stem)
        os.makedirs(pst_extract_path, exist_ok=True)

        # Open the PST file
        pst_file = pypff.open(pst_path)

        # Get the root folder
        root_folder = pst_file.get_root_folder()

        # Process messages to find attachments
        attachment_count = 0

        # We'll process messages in a similar way as in pst_reader.py
        folders_to_process = [root_folder]

        while folders_to_process:
            folder = folders_to_process.pop()

            # Process subfolders
            if hasattr(folder, 'get_sub_folders'):
                for sub_folder in folder.get_sub_folders():
                    folders_to_process.append(sub_folder)

            # Process messages in the folder
            if hasattr(folder, 'get_messages'):
                for message in folder.get_messages():
                    if message and message.number_of_attachments > 0:
                        # Get message ID for directory structure
                        message_id = message.message_id if message.message_id else str(hash(message))

                        # Create message directory
                        message_dir = os.path.join(pst_extract_path, sanitize_filename(message_id))
                        os.makedirs(message_dir, exist_ok=True)

                        # Process attachments
                        for i in range(message.number_of_attachments):
                            try:
                                attachment = message.get_attachment(i)

                                # Get attachment name
                                attachment_name = attachment.long_filename if attachment.long_filename else attachment.filename
                                if not attachment_name:
                                    attachment_name = f"attachment_{i}"

                                # Create full path
                                attachment_path = os.path.join(message_dir, sanitize_filename(attachment_name))

                                # Save attachment
                                if attachment.data:
                                    with open(attachment_path, 'wb') as f:
                                        f.write(attachment.data)

                                    # Calculate SHA256
                                    sha256 = calculate_sha256(attachment_path)

                                    # Save to database
                                    db.insert_attachment(pst_path, message_id, attachment_name, attachment_path, sha256)
                                    attachment_count += 1

                                    if attachment_count % 100 == 0:
                                        logger.info(f"Csatolmány mentése: {attachment_count} db")

                            except Exception as e:
                                logger.error(f"Hiba a csatolmány feldolgozása közben: {str(e)}")
                                continue

        # Close the PST file
        pst_file.close()

        logger.info(f"Csatolmányok mentése befejezve: {attachment_count} db")
        return attachment_count

    except Exception as e:
        logger.error(f"Hiba a csatolmányok mentése közben: {pst_path} - {str(e)}")
        raise e