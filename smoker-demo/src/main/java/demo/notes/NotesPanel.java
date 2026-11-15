package demo.notes;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Demo panel for the automatic names (WP-09). Its components are in private fields and have no name.
 * With {@code -Ddemo.realNames=true} the constructor adds real names: the test of a name that comes after the golden.
 * The package is not {@code smoker.*}: the agent does not treat that prefix as application code.
 */
public class NotesPanel extends JPanel {

    private static final boolean REAL_NAMES = Boolean.getBoolean("demo.realNames");

    private final JTextField noteField = new JTextField(12);
    private final JButton saveButton = new JButton("Save");
    private final JLabel savedLabel = new JLabel("-");

    public NotesPanel() {
        super(new FlowLayout(FlowLayout.LEFT));
        savedLabel.setName("notes.saved");
        if (REAL_NAMES) {
            noteField.setName("notes.txt.note");
            saveButton.setName("notes.btn.save");
        }
        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                savedLabel.setText(noteField.getText());
            }
        });
        add(noteField);
        add(saveButton);
        add(savedLabel);
    }
}
